package com.dream.driftpool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure multi-subscription format and merge rules. Keeps the preceding single-source format readable. */
record SubscriptionBook(List<SubscriptionBook.Source> sources) {
    static final int MAX_SOURCES = 20;
    static final int MAX_POOL_NODES = 1024;
    record Source(String id, String url, String body, long updatedAt) {
        String label() { return URI.create(url).getHost(); }
        int serverCount() throws Exception { return Subscription.parse(body).size(); }
    }
    SubscriptionBook { sources = List.copyOf(sources); }

    static SubscriptionBook fromJson(JSONObject object) throws Exception {
        if (object == null) return new SubscriptionBook(List.of());
        if (!object.has("sources")) {
            Source old = new Source("legacy", object.getString("url"), object.getString("body"), 0);
            SubscriptionBook book = new SubscriptionBook(List.of(old)); book.nodes(); return book;
        }
        if (object.getInt("version") != 2) throw new IllegalArgumentException("Unsupported subscription format");
        JSONArray values = object.getJSONArray("sources");
        if (values.length() > MAX_SOURCES) throw new IllegalArgumentException("Too many subscriptions");
        List<Source> sources = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>(), urls = new java.util.HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            String id = value.getString("id"), url = value.getString("url");
            validateUrl(url);
            if (!ids.add(id) || !urls.add(url)) throw new IllegalArgumentException("Duplicate subscription");
            sources.add(new Source(id, url, value.getString("body"), value.getLong("updatedAt")));
        }
        SubscriptionBook book = new SubscriptionBook(sources); book.nodes(); return book;
    }

    JSONObject toJson() throws Exception {
        JSONArray array = new JSONArray();
        for (Source source : sources) array.put(new JSONObject().put("id", source.id()).put("url", source.url())
                .put("body", source.body()).put("updatedAt", source.updatedAt()));
        return new JSONObject().put("version", 2).put("sources", array);
    }

    SubscriptionBook upsert(String url, String body, long checked) throws Exception {
        validateUrl(url); Subscription.parse(body);
        List<Source> result = new ArrayList<>(sources);
        for (int i = 0; i < result.size(); i++) if (result.get(i).url().equals(url)) {
            result.set(i, new Source(result.get(i).id(), url, body, checked));
            SubscriptionBook book = new SubscriptionBook(result); book.nodes(); return book;
        }
        if (result.size() >= MAX_SOURCES) throw new IllegalArgumentException("Too many subscriptions");
        result.add(new Source(UUID.randomUUID().toString(), url, body, checked));
        SubscriptionBook book = new SubscriptionBook(result); book.nodes(); return book;
    }

    SubscriptionBook remove(String id) {
        List<Source> result = new ArrayList<>();
        for (Source source : sources) if (!source.id().equals(id)) result.add(source);
        return new SubscriptionBook(result);
    }

    List<Subscription.Node> nodes() throws Exception {
        Map<String, Subscription.Node> pool = new LinkedHashMap<>();
        for (Source source : sources) for (Subscription.Node node : Subscription.parse(source.body())) {
            JSONObject canonical = new JSONObject(node.outbound().toString()); canonical.remove("tag");
            pool.putIfAbsent(canonical.toString(), node);
            if (pool.size() > MAX_POOL_NODES) throw new IllegalArgumentException("Server pool too large");
        }
        return List.copyOf(pool.values());
    }

    static void validateUrl(String url) throws Exception {
        URI uri = new URI(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("HTTPS subscription required");
    }
}
