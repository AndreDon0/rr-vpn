package com.dream.driftpool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Imports REALITY outbounds and their optional fragmentation dialers from Xray profiles. */
final class XraySubscription {
    private XraySubscription() {}

    static boolean isConfig(String body) {
        return body.startsWith("{") || body.startsWith("[");
    }

    static List<Subscription.Node> parse(String body) throws Exception {
        JSONArray profiles = body.startsWith("[") ? new JSONArray(body) : new JSONArray().put(new JSONObject(body));
        if (profiles.length() > Subscription.MAX_NODES + 1) throw new IllegalArgumentException("Too many profiles");
        Map<String, Subscription.Node> nodes = new LinkedHashMap<>();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            JSONArray outbounds = profile.getJSONArray("outbounds");
            Map<String, JSONObject> byTag = new LinkedHashMap<>();
            List<JSONObject> proxies = new ArrayList<>();
            for (int j = 0; j < outbounds.length(); j++) {
                JSONObject outbound = outbounds.getJSONObject(j);
                String protocol = outbound.getString("protocol");
                if (!Set.of("vless", "freedom", "blackhole", "loopback", "hysteria").contains(protocol))
                    throw new IllegalArgumentException("Unsupported outbound protocol");
                String tag = outbound.optString("tag");
                if (!tag.isEmpty() && byTag.put(tag, outbound) != null)
                    throw new IllegalArgumentException("Duplicate outbound tag");
                if (protocol.equals("vless")) proxies.add(outbound);
            }
            // Provider profiles may contain routing-only loopbacks or optional Hysteria
            // outbounds, which the bundled engine does not register.
            for (JSONObject proxy : proxies) {
                String name = proxies.size() == 1 ? profile.optString("remarks", "VLESS server") : proxy.optString("tag", "VLESS server");
                Subscription.Node node = parseProxy(proxy, byTag, name);
                // A provider can list a pooled profile and a named profile for each server.
                if (proxies.size() == 1) nodes.put(node.tag(), node);
                else nodes.putIfAbsent(node.tag(), node);
                if (nodes.size() > Subscription.MAX_NODES) throw new IllegalArgumentException("Too many servers");
            }
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException("Empty subscription");
        return List.copyOf(nodes.values());
    }

    private static Subscription.Node parseProxy(JSONObject proxy, Map<String, JSONObject> byTag, String name) throws Exception {
        requireFields(proxy, "protocol", "tag", "settings", "streamSettings");
        JSONObject settings = proxy.getJSONObject("settings");
        requireFields(settings, "vnext");
        JSONObject server = only(settings.getJSONArray("vnext"));
        requireFields(server, "address", "port", "users");
        JSONObject user = only(server.getJSONArray("users"));
        requireFields(user, "id", "encryption", "flow", "security", "email", "alterId", "level");
        JSONObject stream = proxy.getJSONObject("streamSettings");
        requireFields(stream, "network", "security", "realitySettings", "tlsSettings", "sockopt",
                "tcpSettings", "rawSettings", "grpcSettings", "wsSettings", "xhttpSettings");
        String network = stream.optString("network", "tcp");
        String security = stream.getString("security");
        if (!Set.of("tcp", "raw", "xhttp", "grpc", "ws").contains(network)
                || !Set.of("reality", "tls").contains(security)
                || network.equals("xhttp") != stream.has("xhttpSettings"))
            throw new IllegalArgumentException("Unsupported profile transport");
        for (String field : List.of("tcpSettings", "rawSettings")) {
            if (stream.has(field) && stream.getJSONObject(field).length() > 0)
                throw new IllegalArgumentException("Unsupported TCP options");
        }
        JSONObject transportSecurity = stream.getJSONObject(security.equals("reality") ? "realitySettings" : "tlsSettings");
        JSONObject tlsOptions = null;
        if (security.equals("reality")) {
            requireFields(transportSecurity, "serverName", "fingerprint", "publicKey", "shortId", "spiderX", "show");
        } else {
            requireFields(transportSecurity, "serverName", "fingerprint", "alpn", "allowInsecure",
                    "enableSessionResumption", "verifyPeerCertByName");
            if (transportSecurity.optBoolean("allowInsecure", false)) throw new IllegalArgumentException("Insecure TLS option");
            tlsOptions = new JSONObject();
            if (transportSecurity.has("enableSessionResumption"))
                tlsOptions.put("enableSessionResumption", transportSecurity.getBoolean("enableSessionResumption"));
            if (transportSecurity.has("verifyPeerCertByName")) {
                tlsOptions.put("verifyPeerCertByName", transportSecurity.getString("verifyPeerCertByName"));
            }
        }
        JSONObject fragment = null;
        JSONObject socketOptions = null;
        if (stream.has("sockopt")) {
            JSONObject sockopt = stream.getJSONObject("sockopt");
            requireFields(sockopt, "dialerProxy", "tcpUserTimeout", "tcpKeepAliveIdle", "tcpKeepAliveInterval");
            socketOptions = new JSONObject();
            for (String option : List.of("tcpUserTimeout", "tcpKeepAliveIdle", "tcpKeepAliveInterval")) {
                if (sockopt.has(option)) socketOptions.put(option, sockopt.getInt(option));
            }
            if (sockopt.has("dialerProxy")) fragment = parseFragment(byTag.get(sockopt.getString("dialerProxy")));
        }
        String authority = new URI("vless", null, server.getString("address"), server.getInt("port"),
                null, null, null).getRawAuthority();
        String xhttp = network.equals("xhttp") ? "&extra=" + encode(stream.getJSONObject("xhttpSettings").toString()) : "";
        String link = "vless://" + encode(user.getString("id")) + "@" + authority
                + "?type=" + encode(network) + "&security=" + encode(security)
                + "&encryption=" + encode(user.optString("encryption", "none"))
                + "&flow=" + encode(user.isNull("flow") ? "" : user.getString("flow"))
                + "&sni=" + encode(transportSecurity.getString("serverName"))
                + "&fp=" + encode(transportSecurity.optString("fingerprint", "chrome"));
        if (security.equals("reality")) {
            link += "&pbk=" + encode(transportSecurity.getString("publicKey"))
                    + "&sid=" + encode(transportSecurity.optString("shortId"))
                    + "&spx=" + encode(transportSecurity.optString("spiderX"));
        } else if (transportSecurity.has("alpn")) {
            List<String> protocols = new ArrayList<>();
            JSONArray alpn = transportSecurity.getJSONArray("alpn");
            for (int i = 0; i < alpn.length(); i++) protocols.add(alpn.getString(i));
            link += "&alpn=" + encode(String.join(",", protocols));
        }
        if (network.equals("grpc")) {
            JSONObject grpc = stream.getJSONObject("grpcSettings");
            requireFields(grpc, "serviceName", "authority", "multiMode");
            link += "&serviceName=" + encode(grpc.optString("serviceName"))
                    + "&authority=" + encode(grpc.optString("authority"))
                    + "&mode=" + (grpc.optBoolean("multiMode", false) ? "multi" : "");
        } else if (network.equals("ws")) {
            JSONObject ws = stream.getJSONObject("wsSettings");
            requireFields(ws, "path", "headers");
            link += "&path=" + encode(ws.optString("path", "/"));
            if (ws.has("headers")) {
                JSONObject headers = ws.getJSONObject("headers");
                requireFields(headers, "Host");
                link += "&host=" + encode(headers.getString("Host"));
            }
        }
        return Subscription.parseNode(link + xhttp + "#" + encode(name), fragment, socketOptions, tlsOptions);
    }

    private static JSONObject parseFragment(JSONObject dialer) throws Exception {
        if (dialer == null || !dialer.getString("protocol").equals("freedom"))
            throw new IllegalArgumentException("Unsupported fragmentation dialer");
        requireFields(dialer, "protocol", "tag", "settings");
        JSONObject settings = dialer.getJSONObject("settings");
        requireFields(settings, "fragment");
        JSONObject fragment = settings.getJSONObject("fragment");
        requireFields(fragment, "packets", "length", "interval");
        String packets = fragment.getString("packets");
        return new JSONObject()
                .put("packets", packets.equals("tlshello") ? packets : range(packets, 1, 32))
                .put("length", range(fragment.getString("length"), 1, 65535))
                .put("interval", range(fragment.getString("interval"), 0, 1000));
    }

    private static String range(String value, int min, int max) {
        if (!value.matches("[0-9]{1,5}(-[0-9]{1,5})?")) throw new IllegalArgumentException("Invalid fragment range");
        String[] parts = value.split("-");
        int lower = Integer.parseInt(parts[0]);
        int upper = Integer.parseInt(parts[parts.length - 1]);
        if (lower < min || upper > max || lower > upper) throw new IllegalArgumentException("Invalid fragment range");
        return lower == upper ? Integer.toString(lower) : lower + "-" + upper;
    }

    private static JSONObject only(JSONArray list) throws Exception {
        if (list.length() != 1) throw new IllegalArgumentException("Expected one VLESS server or user");
        return list.getJSONObject(0);
    }

    private static void requireFields(JSONObject object, String... fields) {
        Set<String> allowed = Set.of(fields);
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("Unsupported profile field");
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }
}
