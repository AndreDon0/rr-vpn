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
        for (String link : List.of(BASE + "&type=xhttp", BASE.replace("reality", "none"),
                BASE + "&type=grpc&flow=xtls-rprx-vision", BASE + "&security=tls", "<html>error</html>")) {
            assertThrows(Exception.class, () -> Subscription.parse(BASE + "&type=tcp\n" + link));
        }
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
        assertEquals("15s", config.getJSONObject("observatory").getString("probeInterval"));
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
}
