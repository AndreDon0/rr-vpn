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

    @Test public void exactPriorityAntifilterBeforeRuBeforeDefault() throws Exception {
        Subscription.Node node = new Subscription.Node("node-test", "test", new JSONObject().put("tag", "node-test").put("protocol", "blackhole"));
        JSONObject config = PoolConfig.build(List.of(node), true, sample(1));
        JSONArray rules = config.getJSONObject("routing").getJSONArray("rules");
        assertEquals(3, rules.length());
        assertEquals("pool", rules.getJSONObject(0).getString("balancerTag"));
        assertEquals(new JSONArray(sample(1).antifilter()).toString(), rules.getJSONObject(0).getJSONArray("ip").toString());
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"));
        assertEquals(2, rules.getJSONObject(1).getJSONArray("ip").length());
        assertEquals("pool", rules.getJSONObject(2).getString("balancerTag"));
        assertEquals("blackhole", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"));
        JSONObject full = PoolConfig.build(List.of(node), true);
        assertEquals(1, full.getJSONObject("routing").getJSONArray("rules").length());
        assertFalse(full.toString().contains("freedom"));
    }

    private RoutingData sample(long timestamp) throws Exception {
        return RoutingData.create(RoutingData.parseList("5.0.0.0/8", 4), RoutingData.parseList("2001:db8::/32", 6),
                RoutingData.parseList("5.1.2.0/24\n8.8.8.8", 0), timestamp);
    }
}
