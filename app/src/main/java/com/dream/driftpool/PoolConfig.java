package com.dream.driftpool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

final class PoolConfig {
    static final String PROBE_URL = "https://www.gstatic.com/generate_204";
    // Local/private destinations, shared carrier space, multicast and limited broadcast.
    // Explicit CIDRs keep this rule independent of downloaded lists and GeoIP databases.
    static final List<String> LOCAL_NETWORKS = List.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16",
            "224.0.0.0/4", "255.255.255.255/32",
            "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8");

    static JSONObject build(List<Subscription.Node> nodes, boolean tun) throws Exception {
        return build(nodes, tun, null);
    }

    static JSONObject build(List<Subscription.Node> nodes, boolean tun, RoutingData smart) throws Exception {
        if (nodes.isEmpty()) throw new IllegalArgumentException("No servers");
        JSONArray outbounds = new JSONArray();
        // Default outbound and all-unavailable pool fallback always block.
        outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole"));
        for (Subscription.Node node : nodes) outbounds.put(node.outbound());
        outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
        JSONArray rules = new JSONArray();
        // Always first, even when smart routing is off or a downloaded list overlaps.
        rules.put(new JSONObject().put("type", "field").put("ip", new JSONArray(LOCAL_NETWORKS))
                .put("outboundTag", "direct"));
        if (smart != null) {
            JSONArray russian = new JSONArray(smart.ru4());
            for (String prefix : smart.ru6()) russian.put(prefix);
            // First matching rule wins: Antifilter overrides RU exclusions on overlaps.
            rules.put(new JSONObject().put("type", "field").put("ip", new JSONArray(smart.antifilter()))
                    .put("balancerTag", "pool"));
            rules.put(new JSONObject().put("type", "field").put("ip", russian).put("outboundTag", "direct"));
        }
        JSONArray inbounds = new JSONArray();
        if (tun) inbounds.put(new JSONObject().put("tag", "tun").put("protocol", "tun")
                .put("settings", new JSONObject().put("name", "drift0").put("MTU", 1500)));
        JSONObject rule = new JSONObject().put("type", "field").put("network", "tcp,udp")
                .put("balancerTag", "pool");
        rules.put(rule);
        JSONObject balancer = new JSONObject().put("tag", "pool")
                .put("selector", new JSONArray().put("node-"))
                .put("fallbackTag", "block")
                .put("strategy", new JSONObject().put("type", "roundRobin"));
        return new JSONObject().put("log", new JSONObject().put("loglevel", "none"))
                .put("stats", new JSONObject())
                .put("policy", new JSONObject().put("system", new JSONObject()
                        .put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
                .put("inbounds", inbounds).put("outbounds", outbounds)
                .put("routing", new JSONObject().put("domainStrategy", "AsIs")
                        .put("rules", rules)
                        .put("balancers", new JSONArray().put(balancer)))
                .put("observatory", new JSONObject().put("subjectSelector", new JSONArray().put("node-"))
                        .put("probeURL", PROBE_URL).put("probeInterval", "60s")
                        .put("enableConcurrency", true));
    }
}
