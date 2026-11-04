package com.dream.driftpool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RoutingDataTest {
    @Test public void parsesBothFamiliesAndNormalizesNetworksWithoutDns() throws Exception {
        assertEquals(List.of("5.1.2.0/24", "5.1.2.3/32"), RoutingData.parseList("5.1.2.9/24\n5.1.2.0/24\n5.1.2.3\n", 4));
        assertEquals(List.of("2001:db8:0:0:0:0:0:0/32"), RoutingData.parseList("2001:db8::1234/32", 6));
    }

    @Test public void rejectsHtmlDomainsDefaultRoutesAndBadFamilies() {
        for (String body : List.of("", "<html>error</html>", "example.com/24", "256.0.0.1/24", "1.2.3/24",
                "0.0.0.0/0", "::/0", "::/129", "2001:db8::/x", "1.1.1.1/33", "01.2.3.4", "fe80::1%wlan0/64"))
            assertThrows(Exception.class, () -> RoutingData.parseList(body, 0));
        assertThrows(Exception.class, () -> RoutingData.parseList("::1/128", 4));
        assertThrows(Exception.class, () -> RoutingData.parseList("1.1.1.1/32", 6));
    }

    @Test public void contentFingerprintIgnoresRefreshTimeButChangesWithRanges() throws Exception {
        RoutingData first = sample(1);
        RoutingData second = sample(2);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.fingerprint(), RoutingData.fromJson(first.toJson()).fingerprint());
        RoutingData changed = RoutingData.create(List.of("5.0.0.0/9"), first.ru6(), first.antifilter(), 1);
        assertNotEquals(first.fingerprint(), changed.fingerprint());
    }

    @Test public void exactPriorityLocalBeforeAntifilterBeforeRuBeforeDefault() throws Exception {
        Subscription.Node node = new Subscription.Node("node-test", "test", new JSONObject().put("tag", "node-test").put("protocol", "blackhole"));
        JSONObject config = PoolConfig.build(List.of(node), true, sample(1));
        JSONArray rules = config.getJSONObject("routing").getJSONArray("rules");
        assertEquals(4, rules.length());
        assertEquals("direct", rules.getJSONObject(0).getString("outboundTag"));
        assertEquals(new JSONArray(PoolConfig.LOCAL_NETWORKS).toString(), rules.getJSONObject(0).getJSONArray("ip").toString());
        assertEquals("pool", rules.getJSONObject(1).getString("balancerTag"));
        assertEquals(new JSONArray(sample(1).antifilter()).toString(), rules.getJSONObject(1).getJSONArray("ip").toString());
        assertEquals("direct", rules.getJSONObject(2).getString("outboundTag"));
        assertEquals(2, rules.getJSONObject(2).getJSONArray("ip").length());
        assertEquals("pool", rules.getJSONObject(3).getString("balancerTag"));
        assertEquals("blackhole", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"));
        JSONObject full = PoolConfig.build(List.of(node), true);
        JSONArray off = full.getJSONObject("routing").getJSONArray("rules");
        assertEquals(2, off.length());
        assertEquals(rules.getJSONObject(0).toString(), off.getJSONObject(0).toString());
        assertEquals("pool", off.getJSONObject(1).getString("balancerTag"));
    }

    @Test public void localBypassCoversLocalRangesWithoutBypassingPublicNeighbours() throws Exception {
        for (String ip : List.of("0.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255", "127.0.0.1",
                "169.254.1.2", "172.16.0.1", "172.31.255.255", "192.168.1.1", "224.0.0.251", "239.255.255.250",
                "255.255.255.255", "::", "::1", "fc00::1", "fdff::1", "fe80::1", "febf::1", "ff02::fb"))
            assertTrue(ip, isLocal(ip));
        for (String ip : List.of("1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.0", "169.253.255.255",
                "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "223.255.255.255",
                "240.0.0.1", "2001:db8::1", "2001:4860:4860::8888", "fbff::1", "fe00::1", "fec0::1"))
            assertFalse(ip, isLocal(ip));
    }

    private boolean isLocal(String ip) throws Exception {
        byte[] address = java.net.InetAddress.getByName(ip).getAddress();
        for (String cidr : PoolConfig.LOCAL_NETWORKS) {
            String[] parts = cidr.split("/"); byte[] network = java.net.InetAddress.getByName(parts[0]).getAddress();
            if (address.length != network.length) continue;
            int bits = Integer.parseInt(parts[1]); boolean matches = true;
            for (int i = 0; i < address.length && bits > 0; i++, bits -= 8) {
                int mask = 0xff << (8 - Math.min(bits, 8));
                if ((address[i] & mask) != (network[i] & mask)) { matches = false; break; }
            }
            if (matches) return true;
        }
        return false;
    }

    private RoutingData sample(long timestamp) throws Exception {
        return RoutingData.create(RoutingData.parseList("5.0.0.0/8", 4), RoutingData.parseList("2001:db8::/32", 6),
                RoutingData.parseList("5.1.2.0/24\n8.8.8.8", 0), timestamp);
    }
}
