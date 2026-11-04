package com.dream.driftpool;

import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/** Native checks run on an emulator. Synthetic endpoints require no subscription credential. */
public class CoreRuntimeTest {
    private String lastNode;
    private static final CoreCallbackHandler CALLBACK = new CoreCallbackHandler() {
        public long startup() { return 0; }
        public long shutdown() { return 0; }
        public long onEmitStatus(long code, String value) { return 0; }
    };

    @Test public void testNativeHealthFilterFallbackAndRecovery() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Seq.setContext(context);
        Libv2ray.initCoreEnv(context.getFilesDir().getAbsolutePath(), "");
        AtomicBoolean healthy = new AtomicBoolean(true);
        ServerSocket server = new ServerSocket(0);
        ServerSocket second = new ServerSocket(0);
        Thread responder = respond(server, "A", healthy);
        Thread secondResponder = respond(second, "B", healthy);
        String probe = "http://127.0.0.1:" + server.getLocalPort() + "/health";
        String traffic = "http://198.51.100.10:80/traffic"; // Redirected by the fixture outbounds, never sent to the Internet.
        Subscription.Node good = new Subscription.Node("node-good", "Good", new JSONObject()
                .put("tag", "node-good").put("protocol", "freedom")
                .put("settings", new JSONObject().put("redirect", "127.0.0.1:" + server.getLocalPort())));
        Subscription.Node other = new Subscription.Node("node-other", "Other", new JSONObject()
                .put("tag", "node-other").put("protocol", "freedom")
                .put("settings", new JSONObject().put("redirect", "127.0.0.1:" + second.getLocalPort())));
        Subscription.Node bad = new Subscription.Node("node-dead", "Dead", new JSONObject()
                .put("tag", "node-dead").put("protocol", "blackhole"));
        JSONObject config = PoolConfig.build(List.of(good, other, bad), false);
        config.put("inbounds", new JSONArray().put(new JSONObject().put("listen", "127.0.0.1")
                .put("port", 10889).put("protocol", "socks").put("settings", new JSONObject().put("auth", "noauth"))));
        config.getJSONObject("observatory").put("probeURL", probe).put("probeInterval", "1s");
        CoreController core = Libv2ray.newCoreController(CALLBACK);
        try {
            core.startLoop(config.toString(), 0);
            assertTrue(core.getIsRunning());
            Thread.sleep(9000);
            java.util.Set<String> selected = new java.util.HashSet<>();
            for (int i = 0; i < 6; i++) { assertEquals(204, proxyRequest(traffic)); selected.add(lastNode); }
            assertEquals(java.util.Set.of("A", "B"), selected);
            healthy.set(false);
            Thread.sleep(9000);
            // /traffic is still reachable through the good outbound, so only fallback blocks it.
            assertEquals(-1, proxyRequest(traffic));
            assertEquals(204, proxyRequest(probe)); assertEquals("A", lastNode); // Local bypass survives a dead pool.
            healthy.set(true);
            Thread.sleep(9000);
            for (int i = 0; i < 4; i++) assertEquals(204, proxyRequest(traffic));
        } finally {
            core.stopLoop();
            assertFalse(core.getIsRunning());
            server.close(); second.close(); responder.join(3000); secondResponder.join(3000);
        }
    }

    private Thread respond(ServerSocket server, String label, AtomicBoolean healthy) {
        Thread responder = new Thread(() -> {
            while (!server.isClosed()) try (Socket socket = server.accept()) {
                socket.setSoTimeout(1500);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String request = reader.readLine();
                while (true) { String line = reader.readLine(); if (line == null || line.isEmpty()) break; }
                // Fail only the probe path; application requests remain reachable. This lets
                // the test distinguish blackhole fallback from using a failed-observation node.
                if (!healthy.get() && request != null && request.contains("/health")) continue;
                socket.getOutputStream().write(("HTTP/1.1 204 No Content\r\nX-Node: " + label + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
            } catch (Exception ignored) { }
        });
        responder.start();
        return responder;
    }

    private int proxyRequest(String url) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 10889), 2000);
            socket.setSoTimeout(2000);
            java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
            java.io.OutputStream out = socket.getOutputStream();
            out.write(new byte[]{5, 1, 0});
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0) return -1;
            int port = new URL(url).getPort();
            byte[] target = java.net.InetAddress.getByName(new URL(url).getHost().replace("[", "").replace("]", "")).getAddress();
            out.write(new byte[]{5, 1, 0, (byte) (target.length == 4 ? 1 : 4)});
            out.write(target); out.write(new byte[]{(byte) (port >> 8), (byte) port});
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0) return -1;
            in.readUnsignedByte();
            int addressType = in.readUnsignedByte();
            int length = addressType == 1 ? 4 : addressType == 4 ? 16 : in.readUnsignedByte();
            byte[] bound = new byte[length + 2]; in.readFully(bound);
            out.write(("GET /traffic HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line = reader.readLine();
            lastNode = null;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty())
                if (header.startsWith("X-Node: ")) lastNode = header.substring(8);
            return line == null ? -1 : Integer.parseInt(line.split(" ")[1]);
        } catch (Exception e) { return -1; }
    }

    @Test public void testSmartRoutingNativePriorityAndOffSwitch() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Seq.setContext(context); Libv2ray.initCoreEnv(context.getFilesDir().getAbsolutePath(), "");
        ServerSocket direct = new ServerSocket(0), pool = new ServerSocket(0);
        AtomicBoolean healthy = new AtomicBoolean(true);
        Thread a = respond(direct, "DIRECT", healthy), b = respond(pool, "POOL", healthy);
        Subscription.Node node = new Subscription.Node("node-test", "test", new JSONObject()
                .put("tag", "node-test").put("protocol", "freedom")
                .put("settings", new JSONObject().put("redirect", "127.0.0.1:" + pool.getLocalPort())));
        CoreController core = Libv2ray.newCoreController(CALLBACK);
        try {
            // Conflicting local ranges in Antifilter must still go direct. Public overlaps
            // use the pool; RU-only public ranges go direct only while smart routing is on.
            for (boolean enabled : List.of(true, false)) {
                java.util.ArrayList<String> antifilter = new java.util.ArrayList<>(PoolConfig.LOCAL_NETWORKS);
                antifilter.addAll(List.of("5.1.2.0/24", "2001:db8:1::/48", "8.8.8.8/32"));
                RoutingData smart = RoutingData.create(List.of("5.0.0.0/8"), List.of("2001:db8::/32"), antifilter, 1);
                JSONObject config = PoolConfig.build(List.of(node), false, enabled ? smart : null);
                // Intercept both outcomes locally so the exact production routing rules can
                // be checked using synthetic public IPv4/IPv6 addresses without Internet access.
                for (int i=0; i<config.getJSONArray("outbounds").length(); i++) {
                    JSONObject outbound=config.getJSONArray("outbounds").getJSONObject(i);
                    if ("direct".equals(outbound.getString("tag")))
                        outbound.put("settings",new JSONObject().put("redirect","127.0.0.1:"+direct.getLocalPort()));
                }
                config.put("inbounds", new JSONArray().put(new JSONObject().put("listen", "127.0.0.1")
                        .put("port", 10889).put("protocol", "socks").put("settings", new JSONObject().put("auth", "noauth"))));
                config.getJSONObject("observatory").put("probeURL", "http://127.0.0.1:" + direct.getLocalPort() + "/health");
                core.startLoop(config.toString(), 0);
                for (String address : List.of("127.0.0.1", "[::1]", "10.1.2.3", "172.16.0.1", "192.168.1.1",
                        "100.64.0.1", "169.254.1.2", "[fd00::1]", "[fe80::1]", "224.0.0.251", "[ff02::fb]")) {
                    assertEquals(address,204, proxyRequest("http://" + address + ":" + direct.getLocalPort() + "/traffic"));
                    assertEquals(address,"DIRECT",lastNode);
                }
                for (String address : List.of("5.1.2.3", "5.2.3.4", "[2001:db8:1::1]", "[2001:db8:2::1]",
                        "8.8.8.8", "1.1.1.1", "[2001:4860:4860::8888]", "100.63.255.255", "100.128.0.1", "172.32.0.1")) {
                    assertEquals(address,204,proxyRequest("http://"+address+":"+direct.getLocalPort()+"/traffic"));
                    boolean russianOnly=address.equals("5.2.3.4") || address.equals("[2001:db8:2::1]");
                    assertEquals(address,enabled && russianOnly ? "DIRECT" : "POOL",lastNode);
                }
                core.stopLoop();
            }
        } finally { core.stopLoop(); direct.close(); pool.close(); a.join(3000); b.join(3000); }
    }

    @Test public void testRoutingSnapshotStorageAndPersistedJob() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RoutingData data = RoutingLists.load(context);
        assertTrue(data.ru4().size() > 1000); assertTrue(data.ru6().size() > 1000);
        assertTrue(data.antifilter().size() > 1000);
        RoutingLists.publish(context, data);
        RoutingLists.current = null;
        assertEquals(data.fingerprint(), RoutingLists.load(context).fingerprint());
        try {
            RoutingLists.refresh(context, url -> url.equals(RoutingLists.RU4) ? "192.0.2.0/24" : "<html>download failed</html>");
            fail("Malformed feed was accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals(data.fingerprint(), RoutingLists.current.fingerprint());
        assertEquals(data.updatedAt(), RoutingLists.current.updatedAt());
        assertTrue(RoutingLists.lastRefreshFailed);
        RoutingLists.publish(context, data);
        try {
            RoutingLists.setEnabled(context, true);
            android.app.job.JobInfo job = context.getSystemService(android.app.job.JobScheduler.class)
                    .getPendingJob(RoutingLists.PERIODIC_JOB);
            assertNotNull(job); assertTrue(job.isPersisted());
            assertEquals(RoutingLists.INTERVAL, job.getIntervalMillis());
        } finally { RoutingLists.setEnabled(context, false); }
        assertNull(context.getSystemService(android.app.job.JobScheduler.class).getPendingJob(RoutingLists.PERIODIC_JOB));
        Seq.setContext(context); Libv2ray.initCoreEnv(context.getFilesDir().getAbsolutePath(), "");
        CoreController core = Libv2ray.newCoreController(CALLBACK);
        Subscription.Node node = new Subscription.Node("node-test", "test", new JSONObject()
                .put("tag", "node-test").put("protocol", "blackhole"));
        try { core.startLoop(PoolConfig.build(List.of(node), false, data).toString(), 0); assertTrue(core.getIsRunning()); }
        finally { core.stopLoop(); }
    }

    @Test public void testSubscriptionImportAndVpnLifecycle() throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("subscription");
        org.junit.Assume.assumeNotNull(url); // Never embed a private subscription in source.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String body = SubscriptionFetcher.fetch(url);
        List<Subscription.Node> nodes = Subscription.parse(body);
        assertTrue(nodes.size() > 1);
        SecretStore store = new SecretStore(context);
        store.save(url, body);
        assertTrue(body.equals(store.load().getString("body")));
        Intent start = new Intent(context, PoolVpnService.class);
        context.startForegroundService(start);
        context.startForegroundService(start);
        try {
            waitPhase(2);
            assertEquals(nodes.size(), PoolVpnService.snapshot.nodes().size());
            Thread.sleep(15000);
            assertEquals(2, PoolVpnService.snapshot.phase());
            long generation = PoolVpnService.routingGeneration;
            RoutingLists.setEnabled(context, true);
            waitRoutingGeneration(generation);
            assertEquals(RoutingLists.load(context).fingerprint(), PoolVpnService.appliedRoutingKey);
            generation = PoolVpnService.routingGeneration;
            RoutingData original = RoutingLists.load(context);
            java.util.ArrayList<String> changed = new java.util.ArrayList<>(original.ru4());
            changed.add("192.0.2.0/24");
            RoutingLists.publish(context, RoutingData.create(changed, original.ru6(), original.antifilter(), System.currentTimeMillis()));
            waitRoutingGeneration(generation);
            generation = PoolVpnService.routingGeneration;
            RoutingLists.setEnabled(context, false);
            waitRoutingGeneration(generation);
            assertEquals("off", PoolVpnService.appliedRoutingKey);
            RoutingLists.publish(context, original);
        } finally {
            context.startService(new Intent(context, PoolVpnService.class).setAction(PoolVpnService.STOP));
            waitPhase(0);
        }
        // Start, immediate stop, then a cold restart checks startup/teardown serialization.
        context.startForegroundService(start);
        context.startService(new Intent(context, PoolVpnService.class).setAction(PoolVpnService.STOP));
        waitPhase(0);
        Thread.sleep(1000);
        context.startForegroundService(start);
        try { waitPhase(2); }
        finally {
            context.startService(new Intent(context, PoolVpnService.class).setAction(PoolVpnService.STOP));
            waitPhase(0);
        }
    }

    private void waitRoutingGeneration(long previous) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) {
            if (PoolVpnService.snapshot.phase() == 4) fail("VPN routing reload failed");
            if (PoolVpnService.routingGeneration > previous && PoolVpnService.snapshot.phase() == 2) return;
            Thread.sleep(100);
        }
        fail("Routing changes were not applied");
    }

    private void waitPhase(int phase) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) {
            if (PoolVpnService.snapshot.phase() == phase) return;
            if (PoolVpnService.snapshot.phase() == 4) fail("VPN startup failed");
            Thread.sleep(100);
        }
        fail("Timed out waiting for VPN phase " + phase);
    }
}
