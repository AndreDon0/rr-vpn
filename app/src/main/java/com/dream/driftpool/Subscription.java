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

/** Strict VLESS import. Unsupported transports/options fail the whole refresh atomically. */
final class Subscription {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_NODES = 128;
    record Node(String tag, String name, JSONObject outbound) {}

    static String decodeBody(String text) throws Exception {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Subscription too large");
        text = text.trim();
        if (!text.startsWith("vless://") && !text.startsWith("#")) {
            String compact = text.replaceAll("\\s", "");
            try { text = new String(Base64.getDecoder().decode(compact), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) {
                text = new String(Base64.getUrlDecoder().decode(compact), StandardCharsets.UTF_8);
            }
        }
        return text;
    }

    static List<Node> parse(String text) throws Exception {
        text = decodeBody(text);
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
                "allowInsecure").containsAll(q.keySet()))
            throw new IllegalArgumentException("Unsupported VLESS option");
        String network = q.getOrDefault("type", "tcp");
        String security = q.getOrDefault("security", "none");
        if (!List.of("tcp", "raw", "grpc", "ws").contains(network)
                || !List.of("reality", "tls").contains(security))
            throw new IllegalArgumentException("Unsupported transport or unencrypted server");
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
        }
        // The tag does not reveal endpoint names or credentials. Ignore display fragments for deduplication.
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                link.split("#", 2)[0].getBytes(StandardCharsets.UTF_8));
        StringBuilder tag = new StringBuilder("node-");
        for (int i = 0; i < 12; i++) tag.append(String.format("%02x", digest[i]));
        String name = uri.getRawFragment() == null ? "VLESS server" : decode(uri.getRawFragment());
        name = name.replaceAll("[\\p{Cntrl}]", " ");
        if (name.length() > 100) name = name.substring(0, 100);
        JSONObject outbound = new JSONObject().put("tag", tag.toString()).put("protocol", "vless")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(server)))
                .put("streamSettings", stream).put("mux", new JSONObject().put("enabled", false));
        return new Node(tag.toString(), name, outbound);
    }

    private static String decode(String s) throws java.io.UnsupportedEncodingException {
        return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
    }
}
