package com.dream.driftpool;

import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Provider-reported account totals in bytes, and expiry in Unix seconds. -1 means unknown. */
record SubscriptionUsage(long upload, long download, long total, long expire) {
    static final SubscriptionUsage UNKNOWN = new SubscriptionUsage(-1, -1, -1, -1);
    boolean known() { return !equals(UNKNOWN); }
    long used() {
        if (upload < 0 || download < 0) return -1;
        return upload > Long.MAX_VALUE - download ? Long.MAX_VALUE : upload + download;
    }
    int progress() { return total > 0 && used() >= 0 ? (int)Math.min(1000, 1000d * used() / total) : 0; }
    static SubscriptionUsage parse(String value) {
        if (value == null || value.length() > 4096) return UNKNOWN;
        Map<String, Long> fields = new HashMap<>(); Set<String> duplicates = new HashSet<>();
        for (String part : value.split(";")) {
            String[] pair = part.trim().split("=", 2); if (pair.length != 2) continue;
            String key = pair[0].trim().toLowerCase(Locale.ROOT);
            if (!Set.of("upload", "download", "total", "expire").contains(key)) continue;
            if (fields.containsKey(key)) duplicates.add(key);
            long number = -1;
            String digits = pair[1].trim();
            if (digits.matches("[0-9]{1,19}")) try { number = Long.parseLong(digits); } catch (NumberFormatException ignored) { }
            if (key.equals("expire") && number > Long.MAX_VALUE / 1000) number = -1;
            fields.put(key, number);
        }
        for (String duplicate : duplicates) fields.put(duplicate, -1L);
        return new SubscriptionUsage(fields.getOrDefault("upload", -1L), fields.getOrDefault("download", -1L),
                fields.getOrDefault("total", -1L), fields.getOrDefault("expire", -1L));
    }
    static SubscriptionUsage fromResponse(String header, String body) {
        SubscriptionUsage usage = parse(header); if (usage.known()) return usage;
        if (body != null) try {
            String value = null;
            for (String line : Subscription.decodeBody(body).split("\\R")) {
                String trimmed = line.trim(); int colon = trimmed.indexOf(':');
                if (colon > 0 && trimmed.substring(0, colon).equalsIgnoreCase("#subscription-userinfo")) {
                    if (value != null) return UNKNOWN; // Ambiguous duplicate metadata.
                    value = trimmed.substring(colon + 1);
                }
            }
            return parse(value);
        } catch (Exception ignored) { }
        return UNKNOWN;
    }
    JSONObject toJson() throws Exception {
        return new JSONObject().put("upload", upload).put("download", download).put("total", total).put("expire", expire);
    }
    static SubscriptionUsage fromJson(JSONObject value) {
        if (value == null) return UNKNOWN;
        // Reuse strict numeric validation so missing/invalid fields cannot imply unlimited.
        return parse("upload=" + value.opt("upload") + ";download=" + value.opt("download")
                + ";total=" + value.opt("total") + ";expire=" + value.opt("expire"));
    }
}
