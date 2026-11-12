package com.dream.driftpool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

public final class PoolVpnService extends VpnService {
    static final String STOP = "com.dream.driftpool.STOP";
    record Traffic(long up, long down) {}
    record Snapshot(int phase, int message, List<Subscription.Node> nodes, Map<String, Traffic> traffic) {
        boolean busy() { return phase >= 1 && phase <= 3; }
        boolean connected() { return phase == 2 && message == R.string.status_running; }
    }
    static volatile Snapshot snapshot = new Snapshot(0, R.string.status_idle, List.of(), Map.of());
    private static volatile PoolVpnService active;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean trafficRequested = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private ParcelFileDescriptor tunnel;
    private CoreController core;
    private ScheduledFuture<?> connectivityCheck;
    private long lastConnectivityCheck;
    private boolean appliedAppRouting;
    static volatile String appliedRoutingKey = "off";
    static volatile long routingGeneration;
    private List<Subscription.Node> nodes = List.of();
    private final Map<String, Traffic> totals = new HashMap<>();
    private String notificationLanguage = "";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            requestStop(R.string.status_idle);
            return START_NOT_STICKY;
        }
        if (started.compareAndSet(false, true)) {
            // Android may deliver a null intent or SERVICE_INTERFACE for always-on/restart.
            // Foreground entry precedes disk, native and network work.
            showNotification(R.string.status_starting, 0);
            active = this;
            snapshot = new Snapshot(1, R.string.status_starting, List.of(), Map.of());
            worker.execute(this::startPool);
        }
        return START_STICKY;
    }

    private void showNotification(int status, int count) {
        android.content.Context language = AppLanguage.wrap(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("pool", language.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PoolVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "pool")
                .setSmallIcon(R.drawable.ic_pool).setContentTitle(language.getString(R.string.app_name))
                .setContentText(language.getString(status))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, language.getString(R.string.disconnect), stop).build());
        if (status == R.string.status_running)
            builder.setSubText(language.getResources().getQuantityString(R.plurals.server_count, count, count));
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR;
        startForeground(1, notification);
        notificationLanguage = AppLanguage.selected(this);
    }

    private void startPool() {
        try {
            if (stopping.get()) return;
            nodes = new SecretStore(this).loadBook().nodes();
            if (nodes.isEmpty()) throw new IllegalStateException("No saved pool");
            RoutingLists.schedule(this);
            RoutingData routing = RoutingLists.enabled(this) ? RoutingLists.load(this) : null;
            JSONObject config = PoolConfig.build(nodes, true, routing);
            if (stopping.get()) return;
            if (prepare(this) != null) throw new IllegalStateException("VPN permission missing");
            appliedAppRouting = AppRouting.enabled(this);
            tunnel = establishTunnel(appliedAppRouting);
            if (stopping.get()) return;
            Seq.setContext(getApplicationContext());
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
            core = Libv2ray.newCoreController(new CoreCallbackHandler() {
                @Override public long startup() { return 0; }
                @Override public long shutdown() { return 0; }
                // Native messages can contain endpoints. They are deliberately not logged or displayed.
                @Override public long onEmitStatus(long code, String message) { return 0; }
            });
            core.startLoop(config.toString(), tunnel.getFd());
            if (!core.getIsRunning()) throw new IllegalStateException("Core did not start");
            if (stopping.get()) return;
            appliedRoutingKey = routing == null ? "off" : routing.fingerprint();
            routingGeneration++;
            setTunnelStatus(R.string.status_checking);
            scheduleConnectivityCheck(1);
            worker.scheduleWithFixedDelay(() -> maintain(false), 60, 60, TimeUnit.SECONDS);
        } catch (Exception | LinkageError e) {
            stopping.set(true);
            cleanup();
            snapshot = new Snapshot(4, R.string.status_failed, List.copyOf(nodes), Map.of());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } finally {
            // A stop can race parsing, TUN establishment, or the native startup call.
            if (stopping.get()) cleanup();
        }
    }

    private void scheduleConnectivityCheck(long seconds) {
        if (stopping.get()) return;
        if (connectivityCheck != null) connectivityCheck.cancel(false);
        connectivityCheck = worker.schedule(this::checkConnectivity, seconds, TimeUnit.SECONDS);
    }

    private void checkConnectivity() {
        if (stopping.get() || core == null) return;
        int status = R.string.status_unreachable;
        try {
            // Xray dials a hostname through the active balancer. IP-only bypass rules do
            // not match this internal connection, so a successful check needs a proxy.
            if (core.measureDelay(PoolConfig.PROBE_URL) >= 0) status = R.string.status_running;
        } catch (Exception ignored) { /* The tunnel can stay up and recover on a later check. */ }
        lastConnectivityCheck = SystemClock.elapsedRealtime();
        setTunnelStatus(status);
        scheduleConnectivityCheck(status == R.string.status_running ? 60 : 12);
    }

    private synchronized void setTunnelStatus(int status) {
        if (stopping.get()) return;
        boolean changed = snapshot.message() != status;
        snapshot = new Snapshot(2, status, nodes, Map.copyOf(totals));
        if (changed || !notificationLanguage.equals(AppLanguage.selected(this))) showNotification(status, nodes.size());
    }

    private ParcelFileDescriptor establishTunnel(boolean appRouting) throws PackageManager.NameNotFoundException {
        // Keep native proxy/bootstrap sockets out of the TUN. Other exclusions are optional.
        Builder builder = new Builder().setSession(getString(R.string.app_name)).setMtu(1500)
                .addAddress("172.29.0.1", 30).addRoute("0.0.0.0", 0)
                .addAddress("fd66:6472:6966::1", 126).addRoute("::", 0)
                .addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
                .addDisallowedApplication(getPackageName());
        if (appRouting) {
            for (String packageName : AppRouting.excludedPackages(this)) {
                try { builder.addDisallowedApplication(packageName); }
                catch (PackageManager.NameNotFoundException ignored) { /* Uninstalled during setup. */ }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
        ParcelFileDescriptor next = builder.establish();
        if (next == null) throw new IllegalStateException("VPN interface unavailable");
        return next;
    }

    static void settingsChanged() {
        PoolVpnService service = active;
        if (service != null) service.enqueue(() -> service.maintain(false));
    }

    static void requestTrafficSample() {
        PoolVpnService service = active;
        if (service == null || !service.trafficRequested.compareAndSet(false, true)) return;
        service.enqueue(() -> {
            try { service.maintain(true); }
            finally { service.trafficRequested.set(false); }
        });
    }

    private void enqueue(Runnable action) {
        if (stopping.get()) return;
        try { worker.execute(action); }
        catch (RejectedExecutionException e) {
            if (!stopping.get()) throw e;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        enqueue(() -> maintain(false));
    }

    private void maintain(boolean sample) {
        if (stopping.get() || core == null) return;
        try {
            if (!core.getIsRunning()) {
                requestStop(R.string.status_failed);
                return;
            }
            if (!notificationLanguage.equals(AppLanguage.selected(this))) showNotification(snapshot.message(), nodes.size());
            RoutingData routing = RoutingLists.enabled(this) ? RoutingLists.load(this) : null;
            String key = routing == null ? "off" : routing.fingerprint();
            boolean appRouting = AppRouting.enabled(this);
            boolean replaceTunnel = appRouting != appliedAppRouting;
            boolean reload = replaceTunnel || !key.equals(appliedRoutingKey);
            boolean trafficChanged = (sample || reload) && collectTraffic();
            if (reload) {
                JSONObject config = PoolConfig.build(nodes, true, routing);
                setTunnelStatus(R.string.status_reloading);
                if (replaceTunnel) {
                    // Android hands traffic to the new TUN before the old owner's FD closes.
                    // Xray keeps its own duplicate until stopLoop().
                    ParcelFileDescriptor previous = tunnel;
                    tunnel = establishTunnel(appRouting);
                    previous.close();
                }
                core.stopLoop();
                if (stopping.get()) return;
                core.startLoop(config.toString(), tunnel.getFd());
                if (!core.getIsRunning()) throw new IllegalStateException("Core reload failed");
                appliedRoutingKey = key;
                appliedAppRouting = appRouting;
                routingGeneration++;
                setTunnelStatus(R.string.status_checking);
                scheduleConnectivityCheck(1);
            } else if (trafficChanged) {
                setTunnelStatus(snapshot.message());
            }
            if (sample && snapshot.connected() && SystemClock.elapsedRealtime() - lastConnectivityCheck >= 20000) {
                scheduleConnectivityCheck(0);
            }
        } catch (Exception e) { requestStop(R.string.status_failed); }
    }

    private boolean collectTraffic() {
        boolean changed = false;
        for (String entry : core.queryAllOutboundTrafficStats().split(";")) {
            String[] part = entry.split(",");
            if (part.length != 3 || !part[0].startsWith("node-")) continue;
            long value = Long.parseLong(part[2]);
            if (value == 0) continue;
            Traffic previous = totals.getOrDefault(part[0], new Traffic(0, 0));
            totals.put(part[0], new Traffic(previous.up() + (part[1].equals("uplink") ? value : 0),
                    previous.down() + (part[1].equals("downlink") ? value : 0)));
            changed = true;
        }
        return changed;
    }

    private synchronized void requestStop(int message) {
        if (!stopping.compareAndSet(false, true)) return;
        snapshot = new Snapshot(3, R.string.status_stopping, List.copyOf(snapshot.nodes()), snapshot.traffic());
        worker.execute(() -> {
            cleanup();
            snapshot = new Snapshot(message == R.string.status_failed ? 4 : 0, message, List.copyOf(nodes), Map.of());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void cleanup() {
        if (connectivityCheck != null) {
            connectivityCheck.cancel(false);
            connectivityCheck = null;
        }
        // Executed only on the service's serial worker. Keep the TUN in place until the core stops.
        if (core != null) {
            try { core.stopLoop(); } catch (Exception ignored) { /* TUN still closes below. */ }
            core = null;
        }
        if (tunnel != null) {
            try { tunnel.close(); } catch (Exception ignored) { }
            tunnel = null;
        }
    }

    @Override public void onRevoke() { requestStop(R.string.status_revoked); }

    @Override public void onDestroy() {
        stopping.set(true);
        if (active == this) active = null;
        worker.execute(() -> {
            cleanup();
            if (snapshot.phase() != 4 && snapshot.phase() != 0)
                snapshot = new Snapshot(0, R.string.status_idle, List.copyOf(nodes), Map.of());
        });
        worker.shutdown();
        super.onDestroy();
    }
}
