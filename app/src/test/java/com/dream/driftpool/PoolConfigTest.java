package com.dream.driftpool;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import static org.junit.Assert.*;

public class PoolConfigTest {
    private static final String ID = "12345678-1234-4234-8234-123456789abc";
    private static final String BASE = "vless://" + ID + "@vpn.example:443?security=reality&pbk=test-key&sni=example.com&fp=chrome&sid=abcd";

    @Test public void preservesRealityVisionAndGrpc() throws Exception {
        List<Subscription.Node> nodes = Subscription.parse(BASE + "&type=tcp&flow=xtls-rprx-vision#TCP\n"
                + BASE + "&type=grpc&serviceName=a%2Fb&authority=example.com&mode=multi#gRPC");
        assertEquals(2, nodes.size());
        JSONObject tcp = nodes.get(0).outbound();
        assertEquals("xtls-rprx-vision", tcp.getJSONObject("settings").getJSONArray("vnext")
                .getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("flow"));
        assertEquals("test-key", tcp.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("publicKey"));
        JSONObject grpc = nodes.get(1).outbound().getJSONObject("streamSettings").getJSONObject("grpcSettings");
        assertEquals("a/b", grpc.getString("serviceName"));
        assertTrue(grpc.getBoolean("multiMode"));
    }

    @Test public void base64AndDuplicateDisplayNamesDoNotDuplicateServers() throws Exception {
        String input = BASE + "&type=tcp#One\n" + BASE + "&type=tcp#Other";
        assertEquals(1, Subscription.parse(Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8))).size());
        assertEquals(1, Subscription.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8))).size());
    }

    @Test public void keepsLiteralPlusInPathAndLabel() throws Exception {
        Subscription.Node node = Subscription.parseNode(BASE + "&type=ws&path=%2Fhello+world#A+B");
        assertEquals("A+B", node.name());
        assertEquals("/hello+world", node.outbound().getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"));
    }

    @Test public void invalidOrUnsupportedEntryRejectsWholeSubscription() {
        for (String link : List.of(BASE + "&type=kcp", BASE.replace("reality", "none"),
                BASE + "&type=grpc&flow=xtls-rprx-vision", BASE + "&security=tls", "<html>error</html>")) {
            assertThrows(Exception.class, () -> Subscription.parse(BASE + "&type=tcp\n" + link));
        }
    }

    @Test public void mixedFeedsKeepXhttpTransportSettings() throws Exception {
        String options = """
                {"mode":"auto","path":"/test","xPaddingBytes":"100-1000","scMaxEachPostBytes":1000000,
                 "scMaxConcurrentPosts":100,"scMinPostsIntervalMs":30,"noGRPCHeader":false,"headers":{"Pragma":"no-cache"}}
                """;
        String link = BASE + "&type=xhttp&extra=" + java.net.URLEncoder.encode(options, StandardCharsets.UTF_8).replace("+", "%20");
        List<Subscription.Node> nodes = Subscription.parse(BASE + "&type=tcp\n" + link);
        assertEquals(2, nodes.size());
        JSONObject stream = nodes.get(1).outbound().getJSONObject("streamSettings");
        JSONObject xhttp = stream.getJSONObject("xhttpSettings");
        assertEquals("xhttp", stream.getString("network"));
        assertEquals("/test", xhttp.getString("path"));
        assertEquals("100-1000", xhttp.getString("xPaddingBytes"));
        assertEquals(1000000, xhttp.getInt("scMaxEachPostBytes"));
        assertEquals("no-cache", xhttp.getJSONObject("headers").getString("pragma"));

        JSONObject outbound = new JSONObject(nodes.get(1).outbound().toString());
        outbound.remove("mux");
        outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0).put("flow", JSONObject.NULL);
        JSONObject profile = new JSONObject().put("remarks", "XHTTP").put("outbounds", new org.json.JSONArray().put(outbound));
        assertEquals(nodes.get(1).tag(), Subscription.parse(profile.toString()).get(0).tag());
        outbound.getJSONObject("streamSettings").getJSONObject("xhttpSettings").put("downloadSettings", new JSONObject());
        assertThrows(Exception.class, () -> Subscription.parse(profile.toString()));

        JSONObject nested = new JSONObject().put("mode", "auto").put("path", "/nested").put("extra",
                new JSONObject().put("xmux", new JSONObject().put("maxConnections", 3).put("hMaxRequestTimes", "600-900")));
        Subscription.Node nestedNode = Subscription.parseNode(BASE + "&type=xhttp&extra="
                + java.net.URLEncoder.encode(nested.toString(), StandardCharsets.UTF_8));
        JSONObject imported = nestedNode.outbound().getJSONObject("streamSettings").getJSONObject("xhttpSettings");
        assertEquals("/nested", imported.getString("path"));
        assertEquals(3, imported.getJSONObject("xmux").getInt("maxConnections"));
        assertEquals("600-900", imported.getJSONObject("xmux").getString("hMaxRequestTimes"));
    }

    @Test public void rejectsOversizedOrUnboundedPools() {
        assertThrows(Exception.class, () -> Subscription.parse("x".repeat(Subscription.MAX_BYTES + 1)));
        StringBuilder input = new StringBuilder();
        for (int i = 0; i <= Subscription.MAX_NODES; i++) input.append(BASE.replace(":443?", ":" + (1000 + i) + "?")).append("&type=tcp\n");
        assertThrows(Exception.class, () -> Subscription.parse(input.toString()));
    }

    @Test public void publicTrafficUsesHealthFilteringAndAllUnavailableBlocks() throws Exception {
        JSONObject config = PoolConfig.build(Subscription.parse(BASE + "&type=tcp"), true);
        JSONObject routing = config.getJSONObject("routing");
        assertEquals("direct", routing.getJSONArray("rules").getJSONObject(0).getString("outboundTag"));
        assertEquals("pool", routing.getJSONArray("rules").getJSONObject(1).getString("balancerTag"));
        assertFalse(routing.getJSONArray("rules").getJSONObject(1).has("ip"));
        JSONObject balancer = routing.getJSONArray("balancers").getJSONObject(0);
        assertEquals("roundRobin", balancer.getJSONObject("strategy").getString("type"));
        assertEquals("block", balancer.getString("fallbackTag"));
        assertEquals("blackhole", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"));
        assertTrue(config.getJSONObject("observatory").getBoolean("enableConcurrency"));
        assertEquals("60s", config.getJSONObject("observatory").getString("probeInterval"));
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("protocol"));
        assertEquals(2, routing.getJSONArray("rules").length());
    }

    @Test public void ipv6AndTlsArePreserved() throws Exception {
        Subscription.Node node = Subscription.parseNode("vless://" + ID + "@[2001:db8::1]:443?security=tls&type=ws&sni=example.com&alpn=h2,http%2F1.1");
        assertEquals("2001:db8::1", node.outbound().getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"));
        JSONObject tls = node.outbound().getJSONObject("streamSettings").getJSONObject("tlsSettings");
        assertFalse(tls.getBoolean("allowInsecure"));
        assertEquals(2, tls.getJSONArray("alpn").length());
    }

    @Test public void importsTlsAndGrpcProfilesWithSocketOptions() throws Exception {
        JSONObject tls = new JSONObject(Subscription.parseNode("vless://" + ID
                + "@vpn.example:443?security=tls&type=tcp&sni=example.com").outbound().toString());
        tls.remove("mux");
        JSONObject stream = tls.getJSONObject("streamSettings");
        stream.put("tcpSettings", new JSONObject());
        stream.getJSONObject("tlsSettings").put("enableSessionResumption", true)
                .put("verifyPeerCertByName", "example.com");
        stream.put("sockopt", new JSONObject().put("tcpUserTimeout", 10000).put("tcpKeepAliveIdle", 30));
        JSONObject grpc = new JSONObject(Subscription.parseNode(BASE
                + "&type=grpc&serviceName=test&mode=multi").outbound().toString());
        grpc.remove("mux");
        org.json.JSONArray outbounds = new org.json.JSONArray().put(tls).put(grpc)
                .put(new JSONObject().put("tag", "provider-routing").put("protocol", "loopback"))
                .put(new JSONObject().put("tag", "optional-udp").put("protocol", "hysteria"));
        List<Subscription.Node> nodes = Subscription.parse(new JSONObject().put("outbounds", outbounds).toString());
        assertEquals(2, nodes.size());
        JSONObject imported = nodes.get(0).outbound().getJSONObject("streamSettings");
        assertEquals(10000, imported.getJSONObject("sockopt").getInt("tcpUserTimeout"));
        assertTrue(imported.getJSONObject("tlsSettings").getBoolean("enableSessionResumption"));
        assertEquals("example.com", imported.getJSONObject("tlsSettings").getString("verifyPeerCertByName"));
        assertTrue(nodes.get(1).outbound().getJSONObject("streamSettings").getJSONObject("grpcSettings").getBoolean("multiMode"));
    }

    @Test public void importsFragmentationWithoutTakingOverRouting() throws Exception {
        String profile = """
                {
                  "remarks": "Named server",
                  "routing": {"rules": [{"outboundTag": "direct"}]},
                  "outbounds": [
                    {"tag": "proxy", "protocol": "vless",
                     "settings": {"vnext": [{"address": "vpn.example", "port": 443, "users": [
                       {"id": "12345678-1234-4234-8234-123456789abc", "encryption": "none", "flow": "xtls-rprx-vision"}
                     ]}]},
                     "streamSettings": {"network": "tcp", "security": "reality",
                       "realitySettings": {"serverName": "example.com", "publicKey": "test-key"},
                       "sockopt": {"dialerProxy": "provider-dialer"}}},
                    {"tag": "provider-dialer", "protocol": "freedom",
                     "settings": {"fragment": {"packets": "1-3", "length": "100-200", "interval": "10-20"}}}
                  ]
                }
                """;
        List<Subscription.Node> nodes = Subscription.parse("[" + profile + "," + profile + "]");
        assertEquals(1, nodes.size());
        Subscription.Node node = nodes.get(0);
        assertEquals("Named server", node.name());
        JSONObject config = PoolConfig.build(nodes, true);
        JSONObject dialer = config.getJSONArray("outbounds").getJSONObject(2);
        assertEquals(node.outbound().getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"),
                dialer.getString("tag"));
        assertEquals("1-3", dialer.getJSONObject("settings").getJSONObject("fragment").getString("packets"));
        assertEquals("pool", config.getJSONObject("routing").getJSONArray("rules").getJSONObject(1).getString("balancerTag"));
        assertThrows(Exception.class, () -> Subscription.parse(profile.replace("\"fragment\":", "\"redirect\": \"other.example:443\", \"fragment\":")));
        assertThrows(Exception.class, () -> Subscription.parse(profile.replace("\"100-200\"", "\"0-200\"")));
    }
}
