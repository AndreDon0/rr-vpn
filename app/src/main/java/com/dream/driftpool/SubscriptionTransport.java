package com.dream.driftpool;

import android.content.Context;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/** Retries failed subscription downloads through the saved pool for this refresh only. */
final class SubscriptionTransport implements AutoCloseable {
    private final Context context;
    private final List<Subscription.Node> saved;
    private final SubscriptionFetcher.Connections direct;
    private CoreController fallback;
    private Proxy proxy;
    private boolean closed;

    SubscriptionTransport(Context context, List<Subscription.Node> saved) {
        this(context, saved, address -> (HttpURLConnection) address.toURL().openConnection());
    }

    SubscriptionTransport(Context context, List<Subscription.Node> saved, SubscriptionFetcher.Connections direct) {
        this.context = context.getApplicationContext();
        this.saved = saved;
        this.direct = SubscriptionFetcher.deviceConnections(this.context, direct);
    }

    SubscriptionFetcher.Response fetch(String address) throws Exception {
        try {
            return SubscriptionFetcher.fetchWithInfo(address, direct);
        } catch (SubscriptionFetcher.ProviderException e) {
            throw e;
        } catch (Exception failure) {
            if (saved.isEmpty() || Thread.currentThread().isInterrupted()) throw failure;
            Proxy route = fallbackProxy();
            for (int attempt = 0; attempt < Math.min(3, saved.size()); attempt++) {
                try {
                    return SubscriptionFetcher.fetchWithInfo(address, SubscriptionFetcher.deviceConnections(context,
                            uri -> (HttpURLConnection) uri.toURL().openConnection(route)));
                } catch (SubscriptionFetcher.ProviderException e) {
                    throw e;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) throw e;
                    failure = e;
                }
            }
            throw failure;
        }
    }

    private synchronized Proxy fallbackProxy() throws Exception {
        if (closed) throw new java.io.IOException("Subscription refresh closed");
        if (fallback != null) return proxy;
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        JSONObject config = PoolConfig.build(saved, false);
        // An empty IP rule set sends this HTTPS fetch through the proxy pool.
        config.put("inbounds", new JSONArray().put(new JSONObject().put("listen", "127.0.0.1")
                .put("port", port).put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "noauth").put("udp", false))));
        Seq.setContext(context);
        Libv2ray.initCoreEnv(context.getFilesDir().getAbsolutePath(), "");
        CoreController core = Libv2ray.newCoreController(new CoreCallbackHandler() {
            @Override public long startup() { return 0; }
            @Override public long shutdown() { return 0; }
            @Override public long onEmitStatus(long code, String message) { return 0; }
        });
        try {
            core.startLoop(config.toString(), 0);
            if (!core.getIsRunning()) throw new java.io.IOException("Saved VPN routes unavailable");
            fallback = core;
            proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port));
            return proxy;
        } catch (Exception e) {
            core.stopLoop();
            throw e;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (fallback != null) {
            try { fallback.stopLoop(); } catch (Exception ignored) { }
            fallback = null;
        }
    }
}
