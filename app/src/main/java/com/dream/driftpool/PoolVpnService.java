package com.dream.driftpool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    }
    static volatile Snapshot snapshot = new Snapshot(0, R.string.status_idle, List.of(), Map.of());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private ParcelFileDescriptor tunnel;
    private CoreController core;
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
        // Android may deliver a null intent or SERVICE_INTERFACE for always-on/restart.
        // Foreground entry precedes disk, native and network work.
        showNotification(0);
        if (started.compareAndSet(false, true)) {
            snapshot = new Snapshot(1, R.string.status_starting, List.of(), Map.of());
            worker.execute(this::startPool);
        }
        return START_STICKY;
    }

    private void showNotification(int count) {
        android.content.Context language = AppLanguage.wrap(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("pool", language.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PoolVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "pool")
                .setSmallIcon(R.drawable.ic_pool).setContentTitle(language.getString(R.string.app_name))
                .setContentText(count == 0 ? language.getString(R.string.status_starting) : language.getString(R.string.notification_text, count))
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, language.getString(R.string.disconnect), stop).build()).build();
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
            // Excluding only this app prevents native proxy/bootstrap sockets looping into the TUN.
            // All other apps' IPv4 and IPv6 traffic, including DNS, enters Xray's ordered routing.
            Builder builder = new Builder().setSession(getString(R.string.app_name)).setMtu(1500)
                    .addAddress("172.29.0.1", 30).addRoute("0.0.0.0", 0)
                    .addAddress("fd66:6472:6966::1", 126).addRoute("::", 0)
                    .addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
                    .addDisallowedApplication(getPackageName());
            if (android.os.Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            tunnel = builder.establish();
            if (tunnel == null) throw new IllegalStateException("VPN interface unavailable");
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
            snapshot = new Snapshot(2, R.string.status_running, List.copyOf(nodes), Map.of());
            showNotification(nodes.size());
            worker.scheduleWithFixedDelay(this::sampleTraffic, 2, 2, TimeUnit.SECONDS);
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

    private void sampleTraffic() {
        if (stopping.get()) return;
        try {
            if (core == null || !core.getIsRunning()) {
                requestStop(R.string.status_failed);
                return;
            }
            if (!notificationLanguage.equals(AppLanguage.selected(this))) showNotification(nodes.size());
            RoutingData routing = RoutingLists.enabled(this) ? RoutingLists.load(this) : null;
            String key = routing == null ? "off" : routing.fingerprint();
            if (!key.equals(appliedRoutingKey)) {
                // Keep Android's TUN established during reload, so captured packets cannot bypass it.
                // Xray's Android TUN duplicates the supplied FD and leaves this owner's FD open.
                JSONObject config = PoolConfig.build(nodes, true, routing);
                snapshot = new Snapshot(2, R.string.status_reloading, List.copyOf(nodes), Map.copyOf(totals));
                core.stopLoop();
                if (stopping.get()) return;
                core.startLoop(config.toString(), tunnel.getFd());
                if (!core.getIsRunning()) throw new IllegalStateException("Core reload failed");
                appliedRoutingKey = key;
                routingGeneration++;
            }
            for (String entry : core.queryAllOutboundTrafficStats().split(";")) {
                String[] part = entry.split(",");
                if (part.length != 3 || !part[0].startsWith("node-")) continue;
                Traffic previous = totals.getOrDefault(part[0], new Traffic(0, 0));
                long value = Long.parseLong(part[2]);
                totals.put(part[0], new Traffic(previous.up() + (part[1].equals("uplink") ? value : 0),
                        previous.down() + (part[1].equals("downlink") ? value : 0)));
            }
            snapshot = new Snapshot(2, R.string.status_running, List.copyOf(nodes),
                    Collections.unmodifiableMap(new HashMap<>(totals)));
        } catch (Exception e) { requestStop(R.string.status_failed); }
    }

    private void requestStop(int message) {
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
        worker.execute(() -> {
            cleanup();
            if (snapshot.phase() != 4 && snapshot.phase() != 0)
                snapshot = new Snapshot(0, R.string.status_idle, List.copyOf(nodes), Map.of());
        });
        worker.shutdown();
        super.onDestroy();
    }
}
