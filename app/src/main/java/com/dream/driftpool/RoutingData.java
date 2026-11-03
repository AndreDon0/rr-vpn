package com.dream.driftpool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Validated public CIDR data, independent of Android storage and scheduling. */
record RoutingData(List<String> ru4, List<String> ru6, List<String> antifilter, long updatedAt, String fingerprint) {
    static List<String> parseList(String body, int family) throws Exception {
        if (body.length() > Subscription.MAX_BYTES) throw new IllegalArgumentException("IP list too large");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String line : body.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            result.add(cidr(value, family));
            if (result.size() > 150000) throw new IllegalArgumentException("Too many IP ranges");
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty IP list");
        return List.copyOf(result);
    }

    private static String cidr(String value, int family) throws Exception {
        String[] parts = value.split("/", -1);
        if (parts.length > 2 || parts[0].isEmpty()) throw new IllegalArgumentException("Invalid CIDR");
        boolean v6 = parts[0].contains(":");
        if ((family == 4 && v6) || (family == 6 && !v6)) throw new IllegalArgumentException("Wrong address family");
        byte[] address;
        if (v6) {
            // Reject domains, scope IDs, brackets and all other non-literal input before parsing.
            if (!parts[0].matches("[0-9a-fA-F:]+")) throw new IllegalArgumentException("Invalid IPv6");
            address = InetAddress.getByName(parts[0]).getAddress();
            if (address.length != 16) throw new IllegalArgumentException("Invalid IPv6");
        } else {
            String[] octets = parts[0].split("\\.", -1);
            if (octets.length != 4) throw new IllegalArgumentException("Invalid IPv4");
            address = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid IPv4");
                int n = Integer.parseInt(octets[i]);
                if (n > 255) throw new IllegalArgumentException("Invalid IPv4");
                address[i] = (byte) n;
            }
        }
        int bits = address.length * 8;
        if (parts.length == 2 && !parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid prefix");
        int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : bits;
        // A corrupted /0 would silently bypass the whole internet. None of these feeds needs /0.
        if (prefix < 1 || prefix > bits) throw new IllegalArgumentException("Invalid prefix");
        for (int bit = prefix; bit < bits; bit++) address[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
        return InetAddress.getByAddress(address).getHostAddress() + "/" + prefix;
    }

    static RoutingData fromJson(String json) throws Exception {
        JSONObject object = new JSONObject(json);
        return create(parseArray(object.getJSONArray("ru4"), 4), parseArray(object.getJSONArray("ru6"), 6),
                parseArray(object.getJSONArray("antifilter"), 0), object.getLong("updatedAt"));
    }

    private static List<String> parseArray(JSONArray array, int family) throws Exception {
        if (array.length() > 150000) throw new IllegalArgumentException("Too many IP ranges");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < array.length(); i++) body.append(array.getString(i)).append('\n');
        return parseList(body.toString(), family);
    }

    static RoutingData create(List<String> ru4, List<String> ru6, List<String> antifilter, long timestamp) throws Exception {
        String content = String.join("\n", ru4) + "\n--\n" + String.join("\n", ru6) + "\n--\n" + String.join("\n", antifilter);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : digest) hash.append(String.format("%02x", b));
        return new RoutingData(List.copyOf(ru4), List.copyOf(ru6), List.copyOf(antifilter), timestamp, hash.toString());
    }

    String toJson() throws Exception {
        return new JSONObject().put("updatedAt", updatedAt).put("ru4", new JSONArray(ru4))
                .put("ru6", new JSONArray(ru6)).put("antifilter", new JSONArray(antifilter)).toString();
    }
}
