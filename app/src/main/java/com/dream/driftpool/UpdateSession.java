package com.dream.driftpool;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

/** Process-owned download state. Views observe it; rotating a screen never owns/cancels the transfer. */
final class UpdateSession {
    enum Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, WAITING_WIFI, PAUSED, ERROR }
    record Snapshot(UpdateRelease release, Phase phase, long downloaded, long total) {
        boolean visible() { return release != null && phase != Phase.IDLE && phase != Phase.CHECKING; }
        float progress() { return total <= 0 ? 0 : Math.min(1f, (float) downloaded / total); }
    }
    interface Observer { void changed(Snapshot snapshot); }
    private static UpdateSession instance;
    static synchronized UpdateSession get(Context context) {
        if (instance == null) instance = new UpdateSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Observer> observers = new HashSet<>();
    private volatile Snapshot snapshot = new Snapshot(null, Phase.IDLE, 0, 0);
    private volatile boolean foreground;
    private boolean running, again, forceNext, watching;
    private long lastProgress;
    Snapshot snapshot() { return snapshot; }
    private UpdateSession(Context context) { this.context = context; UpdateManager.cancelLegacyJob(context); }
    private final ConnectivityManager.NetworkCallback networkChanged = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { main.post(() -> request(false)); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { main.post(() -> request(false)); }
        @Override public void onLost(Network network) { main.post(() -> request(false)); }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (foreground) { request(false); main.postDelayed(this, 30000); } }
    };
    private final Runnable stop = this::stopWhenHidden;
    private void stopWhenHidden() {
        if (!observers.isEmpty()) return;
        foreground = false; main.removeCallbacks(poll);
        if (watching) {
            context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkChanged); watching = false;
        }
    }
    void attach(Observer observer) {
        observers.add(observer); main.removeCallbacks(stop); foreground = true;
        observer.changed(snapshot);
        if (!watching) {
            context.getSystemService(ConnectivityManager.class).registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkChanged); watching = true;
        }
        main.removeCallbacks(poll); main.post(poll);
    }
    void detach(Observer observer) {
        observers.remove(observer);
        if (observers.isEmpty()) main.postDelayed(stop, 500); // Configuration changes reattach before this expires.
    }
    void request(boolean force) {
        if (!foreground) return;
        forceNext |= force;
        if (running) { again = true; return; }
        boolean checkNow = forceNext; forceNext = false; running = true;
        io.execute(() -> {
            try { refresh(checkNow); }
            finally { main.post(() -> { running = false; if (again) { again = false; request(false); } }); }
        });
    }
    private void publish(Snapshot state) {
        snapshot = state;
        main.post(() -> { for (Observer observer : Set.copyOf(observers)) observer.changed(state); });
    }
    private void refresh(boolean force) {
        UpdateRelease release = snapshot.release();
        try {
            boolean checkFailed = false;
            if (release == null) release = UpdateManager.cached(context);
            if (force || UpdateManager.due(context)) {
                if (!snapshot.visible()) publish(new Snapshot(null, Phase.CHECKING, 0, 0));
                try { release = UpdateManager.check(context); }
                catch (Exception e) { if (release == null) throw e; checkFailed = true; }
            }
            if (!UpdateManager.newer(context, release)) { publish(new Snapshot(null, checkFailed ? Phase.ERROR : Phase.IDLE, 0, 0)); return; }
            UpdateRelease found = release;
            long done = snapshot.release() != null && snapshot.release().sha256().equals(release.sha256()) ? snapshot.downloaded() : UpdateManager.downloaded(context, release);
            if (snapshot.phase() == Phase.READY && snapshot.release() != null && snapshot.release().sha256().equals(release.sha256())
                    && UpdateManager.apk(context).isFile()) { publish(new Snapshot(found, Phase.READY, found.size(), found.size())); return; }
            if (UpdateManager.ready(context, release)) { publish(new Snapshot(found, Phase.READY, found.size(), found.size())); return; }
            if (!foreground) { publish(new Snapshot(found, Phase.PAUSED, done, found.size())); return; }
            if (UpdateManager.downloadNetwork(context) == null) {
                publish(new Snapshot(found, UpdateManager.wifiOnly(context) ? Phase.WAITING_WIFI : Phase.PAUSED, done, found.size())); return;
            }
            publish(new Snapshot(found, Phase.DOWNLOADING, done, found.size()));
            UpdateManager.download(context, found, new UpdateManager.Progress() {
                @Override public void transferred(long bytes, long total) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastProgress >= 80 || bytes == total || bytes == 0) {
                        lastProgress = now; publish(new Snapshot(found, Phase.DOWNLOADING, bytes, total));
                    }
                }
                @Override public void verifying() { publish(new Snapshot(found, Phase.VERIFYING, found.size(), found.size())); }
            }, () -> foreground);
            publish(new Snapshot(found, Phase.READY, found.size(), found.size()));
        } catch (UpdateManager.Paused e) {
            Snapshot previous = snapshot;
            publish(new Snapshot(release, foreground && UpdateManager.wifiOnly(context) ? Phase.WAITING_WIFI : Phase.PAUSED,
                    previous.downloaded(), release == null ? 0 : release.size()));
        } catch (Exception e) {
            Snapshot previous = snapshot;
            publish(new Snapshot(release, Phase.ERROR, previous.downloaded(), release == null ? 0 : release.size()));
        }
    }
}
