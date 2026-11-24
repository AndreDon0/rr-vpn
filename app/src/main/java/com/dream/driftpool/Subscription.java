package com.dream.driftpool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** VLESS import. Unsupported transports/options fail the whole refresh atomically. */
final class Subscription {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_NODES = 128;
    record Node(String tag, String name, JSONObject outbound, JSONObject dialer) {
        Node(String tag, String name, JSONObject outbound) { this(tag, name, outbound, null); }
    }

    static String decodeBody(String text) throws Exception {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Subscription too large");
        text = text.trim();
        if (!text.startsWith("vless://") && !text.startsWith("#") && !XraySubscription.isConfig(text)) {
            String compact = text.replaceAll("\\s", "");
            try { text = new String(Base64.getDecoder().decode(compact), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) {
                text = new String(Base64.getUrlDecoder().decode(compact), StandardCharsets.UTF_8);
            }
        }
        return text;
    }

    static List<Node> parse(String text) throws Exception {
        text = decodeBody(text).trim();
        if (XraySubscription.isConfig(text)) return XraySubscription.parse(text);
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            Node node = parseNode(line.trim());
            nodes.putIfAbsent(node.tag(), node);
            if (nodes.size() > MAX_NODES) throw new IllegalArgumentException("Too many servers");
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException("Empty subscription");
        return new ArrayList<>(nodes.values());
    }

    static Node parseNode(String link) throws Exception {
        return parseNode(link, null);
    }

    static Node parseNode(String link, JSONObject fragment) throws Exception {
        return parseNode(link, fragment, null, null);
    }

    static Node parseNode(String link, JSONObject fragment, JSONObject socketOptions, JSONObject tlsOptions) throws Exception {
        URI uri = new URI(link);
        if (!"vless".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() == null)
            throw new IllegalArgumentException("Invalid VLESS entry");
        String id = decode(uri.getRawUserInfo());
        if (!UUID.fromString(id).toString().equalsIgnoreCase(id))
            throw new IllegalArgumentException("Invalid VLESS user ID");
        Map<String, String> q = new HashMap<>();
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (q.put(decode(kv[0]), kv.length == 2 ? decode(kv[1]) : "") != null)
                throw new IllegalArgumentException("Duplicate option");
        }
        if (!java.util.Set.of("type", "security", "encryption", "flow", "headerType", "sni", "serverName",
                "fp", "pbk", "sid", "spx", "alpn", "serviceName", "authority", "mode", "path", "host",
                "allowInsecure", "extra").containsAll(q.keySet()))
            throw new IllegalArgumentException("Unsupported VLESS option");
        String network = q.getOrDefault("type", "tcp");
        String security = q.getOrDefault("security", "none");
        if (!List.of("tcp", "raw", "grpc", "ws", "xhttp").contains(network)
                || !List.of("reality", "tls").contains(security))
            throw new IllegalArgumentException("Unsupported transport or unencrypted server");
        if (q.containsKey("extra") && !network.equals("xhttp"))
            throw new IllegalArgumentException("Unsupported transport option");
        if (!q.getOrDefault("encryption", "none").equals("none"))
            throw new IllegalArgumentException("Unsupported VLESS encryption");
        if (!List.of("", "none").contains(q.getOrDefault("headerType", "none")))
            throw new IllegalArgumentException("Unsupported TCP header");
        if ("1".equals(q.get("allowInsecure")) || "true".equals(q.get("allowInsecure")))
            throw new IllegalArgumentException("Insecure certificate option");
        String flow = q.getOrDefault("flow", "");
        if (!flow.isEmpty() && (!flow.equals("xtls-rprx-vision")
                || !List.of("tcp", "raw").contains(network)))
            throw new IllegalArgumentException("Unsupported flow");
        JSONObject user = new JSONObject().put("id", id).put("encryption", "none");
        if (!flow.isEmpty()) user.put("flow", flow);
        JSONObject server = new JSONObject().put("address", uri.getHost().replace("[", "").replace("]", ""))
                .put("port", uri.getPort()).put("users", new JSONArray().put(user));
        JSONObject stream = new JSONObject().put("network", network).put("security", security);
        String sni = q.getOrDefault("sni", q.getOrDefault("serverName", uri.getHost()));
        String fingerprint = q.getOrDefault("fp", "chrome");
        if (security.equals("reality")) {
            if (q.getOrDefault("pbk", "").isEmpty()) throw new IllegalArgumentException("Missing Reality key");
            stream.put("realitySettings", new JSONObject().put("serverName", sni)
                    .put("fingerprint", fingerprint).put("publicKey", q.get("pbk"))
                    .put("shortId", q.getOrDefault("sid", ""))
                    .put("spiderX", q.getOrDefault("spx", "")));
        } else {
            JSONObject tls = new JSONObject().put("serverName", sni).put("fingerprint", fingerprint)
                    .put("allowInsecure", false);
            if (q.containsKey("alpn")) tls.put("alpn", new JSONArray(q.get("alpn").split(",")));
            if (tlsOptions != null) {
                java.util.Iterator<String> fields = tlsOptions.keys();
                while (fields.hasNext()) {
                    String field = fields.next();
                    tls.put(field, tlsOptions.get(field));
                }
            }
            stream.put("tlsSettings", tls);
        }
        if (network.equals("grpc")) {
            stream.put("grpcSettings", new JSONObject().put("serviceName", q.getOrDefault("serviceName", ""))
                    .put("authority", q.getOrDefault("authority", ""))
                    .put("multiMode", "multi".equals(q.get("mode"))));
        } else if (network.equals("ws")) {
            JSONObject ws = new JSONObject().put("path", q.getOrDefault("path", "/"));
            if (q.containsKey("host")) ws.put("headers", new JSONObject().put("Host", q.get("host")));
            stream.put("wsSettings", ws);
        } else if (network.equals("xhttp")) {
            JSONObject options = q.containsKey("extra") ? new JSONObject(q.get("extra")) : new JSONObject();
            for (String field : List.of("host", "path", "mode")) {
                if (!q.containsKey(field)) continue;
                if (options.has(field)) throw new IllegalArgumentException("Duplicate XHTTP option");
                options.put(field, q.get(field));
            }
            stream.put("xhttpSettings", xhttpSettings(options));
        }
        // The tag does not reveal endpoint names or credentials. Ignore display fragments for deduplication.
        if (socketOptions != null && socketOptions.length() > 0) stream.put("sockopt", socketOptions);
        String identity = network.equals("xhttp") || socketOptions != null || tlsOptions != null
                ? server + "\n" + stream : link.split("#", 2)[0];
        if (fragment != null) identity += "\n" + fragment;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder tag = new StringBuilder("node-");
        for (int i = 0; i < 12; i++) tag.append(String.format("%02x", digest[i]));
        String name = uri.getRawFragment() == null ? "VLESS server" : decode(uri.getRawFragment());
        name = name.replaceAll("[\\p{Cntrl}]", " ");
        if (name.length() > 100) name = name.substring(0, 100);
        JSONObject outbound = new JSONObject().put("tag", tag.toString()).put("protocol", "vless")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(server)))
                .put("streamSettings", stream).put("mux", new JSONObject().put("enabled", false));
        JSONObject dialer = null;
        if (fragment != null) {
            String dialerTag = "dialer-" + tag.substring("node-".length());
            JSONObject socket = stream.optJSONObject("sockopt");
            if (socket == null) socket = new JSONObject();
            stream.put("sockopt", socket.put("dialerProxy", dialerTag));
            dialer = new JSONObject().put("tag", dialerTag).put("protocol", "freedom")
                    .put("settings", new JSONObject().put("fragment", fragment));
        }
        return new Node(tag.toString(), name, outbound, dialer);
    }

    private static JSONObject xhttpSettings(JSONObject options) throws Exception {
        if (options.has("extra")) {
            if (!(options.get("extra") instanceof JSONObject extra) || extra.has("extra"))
                throw new IllegalArgumentException("Invalid XHTTP extra");
            JSONObject combined = xhttpSettings(extra);
            java.util.Iterator<String> fields = options.keys();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!List.of("extra", "host", "path", "mode").contains(field))
                    throw new IllegalArgumentException("Unsupported XHTTP extra sibling");
            }
            for (String field : List.of("host", "path", "mode")) {
                combined.remove(field);
                if (options.has(field)) combined.put(field, options.get(field));
            }
            return xhttpSettings(combined);
        }
        JSONObject result = new JSONObject();
        for (String field : List.of("host", "path", "mode")) {
            if (!options.has(field)) continue;
            Object value = options.get(field);
            if (!(value instanceof String text) || text.length() > 2048 || text.matches("(?s).*[\\p{Cntrl}].*"))
                throw new IllegalArgumentException("Invalid XHTTP option");
            if (field.equals("mode") && !List.of("", "auto", "packet-up", "stream-up", "stream-one").contains(text))
                throw new IllegalArgumentException("Unsupported XHTTP mode");
            result.put(field, text);
        }
        if (options.has("headers")) {
            JSONObject headers = options.getJSONObject("headers");
            java.util.TreeMap<String, String> normalized = new java.util.TreeMap<>();
            java.util.Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Object value = headers.get(name);
                if (!name.matches("[A-Za-z0-9-]{1,64}") || name.equalsIgnoreCase("host")
                        || !(value instanceof String text) || text.length() > 2048 || text.matches("(?s).*[\\p{Cntrl}].*")
                        || normalized.put(name.toLowerCase(java.util.Locale.ROOT), text) != null)
                    throw new IllegalArgumentException("Invalid XHTTP header");
            }
            result.put("headers", new JSONObject(normalized));
        }
        for (String field : List.of("scMaxEachPostBytes", "scMaxConcurrentPosts", "scMinPostsIntervalMs", "xPaddingBytes")) {
            if (!options.has(field)) continue;
            Object value = options.get(field);
            if (!(value instanceof Integer || value instanceof Long || value instanceof String)
                    || !value.toString().matches("[0-9]{1,9}(-[0-9]{1,9})?"))
                throw new IllegalArgumentException("Invalid XHTTP range");
            String[] range = value.toString().split("-");
            int start = Integer.parseInt(range[0]), end = Integer.parseInt(range[range.length - 1]);
            int maximum = switch (field) {
                case "scMaxEachPostBytes" -> 16 * 1024 * 1024;
                case "scMaxConcurrentPosts" -> 1024;
                default -> 65535;
            };
            if (start > end || end > maximum || (field.equals("xPaddingBytes") && start == 0 && end != 0))
                throw new IllegalArgumentException("Invalid XHTTP range");
            result.put(field, value);
        }
        if (options.has("noGRPCHeader")) {
            Object value = options.get("noGRPCHeader");
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid XHTTP flag");
            result.put("noGRPCHeader", value);
        }
        if (options.has("xmux")) {
            JSONObject xmux = options.getJSONObject("xmux");
            java.util.Iterator<String> fields = xmux.keys();
            while (fields.hasNext()) {
                String field = fields.next();
                Object value = xmux.get(field);
                if (field.equals("hKeepAlivePeriod")) {
                    if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() < -1
                            || ((Number)value).longValue() > 86400) throw new IllegalArgumentException("Invalid XHTTP keepalive");
                    continue;
                }
                if (!List.of("maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes", "hMaxReusableSecs").contains(field)
                        || !(value instanceof Integer || value instanceof Long || value instanceof String)
                        || !value.toString().matches("[0-9]{1,9}(-[0-9]{1,9})?"))
                    throw new IllegalArgumentException("Invalid XHTTP multiplexing option");
                String[] range = value.toString().split("-");
                if (Integer.parseInt(range[0]) > Integer.parseInt(range[range.length - 1]))
                    throw new IllegalArgumentException("Invalid XHTTP multiplexing range");
            }
            result.put("xmux", xmux);
        }
        if (options.length() != result.length()) throw new IllegalArgumentException("Unsupported XHTTP option");
        return result;
    }

    private static String decode(String s) throws java.io.UnsupportedEncodingException {
        return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
    }
}
