package com.dream.driftpool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

final class PoolConfig {
    static final String PROBE_URL = "https://www.gstatic.com/generate_204";

    static JSONObject build(List<Subscription.Node> nodes, boolean tun) throws Exception {
        return build(nodes, tun, null);
    }

    static JSONObject build(List<Subscription.Node> nodes, boolean tun, RoutingData smart) throws Exception {
        if (nodes.isEmpty()) throw new IllegalArgumentException("No servers");
        JSONArray outbounds = new JSONArray();
        // Default outbound and all-unavailable pool fallback always block.
        outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole"));
        for (Subscription.Node node : nodes) outbounds.put(node.outbound());
        JSONArray rules = new JSONArray();
        if (smart != null) {
            outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
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
                        .put("probeURL", PROBE_URL).put("probeInterval", "15s")
                        .put("enableConcurrency", true));
    }
}
