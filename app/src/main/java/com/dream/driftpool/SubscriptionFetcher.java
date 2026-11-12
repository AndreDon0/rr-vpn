package com.dream.driftpool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class SubscriptionFetcher {
    record Response(String body, SubscriptionUsage usage) {}
    interface Connections { HttpURLConnection open(URI address) throws Exception; }
    private static final Connections NETWORK = address -> (HttpURLConnection)address.toURL().openConnection();
    static String fetch(String address) throws Exception { return fetchWithInfo(address).body(); }
    static Response fetchWithInfo(String address) throws Exception { return fetchWithInfo(address, NETWORK); }
    static Response fetchWithInfo(String address, Connections connections) throws Exception { return request(address, false, connections); }
    static SubscriptionUsage fetchUsage(String address) throws Exception {
        return fetchUsage(address, NETWORK);
    }
    static SubscriptionUsage fetchUsage(String address, Connections connections) throws Exception {
        try {
            SubscriptionUsage head = request(address, true, connections).usage();
            if (head.known()) return head;
        } catch (java.io.IOException ignored) { } // Some providers do not implement HEAD.
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        return fetchWithInfo(address, connections).usage();
    }
    private static Response request(String address, boolean head, Connections connections) throws Exception {
        URI uri = new URI(address);
        // DriftVPN's Xray profile contains the dialer settings omitted from its VLESS links.
        String userAgent = "driftvpn.cc".equalsIgnoreCase(uri.getHost()) ? "v2rayNG/1.10.0" : "v2rayNG/DriftPool-0.1";
        for (int redirect = 0; redirect < 4; redirect++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("HTTPS subscription required");
            HttpURLConnection connection = connections.open(uri);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            if (head) connection.setRequestMethod("HEAD");
            connection.setRequestProperty("User-Agent", userAgent);
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IllegalStateException("Missing redirect");
                    uri = uri.resolve(next);
                    continue;
                }
                if (code != 200) throw new java.io.IOException("Subscription request failed");
                String userinfo = connection.getHeaderField("Subscription-Userinfo");
                if (head) return new Response("", SubscriptionUsage.parse(userinfo));
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        if (out.size() + n > Subscription.MAX_BYTES) throw new IllegalArgumentException("Subscription too large");
                        out.write(buffer, 0, n);
                    }
                    String body = out.toString(StandardCharsets.UTF_8.name());
                    return new Response(body, SubscriptionUsage.fromResponse(userinfo, body));
                }
            } finally { connection.disconnect(); }
        }
        throw new IllegalStateException("Too many redirects");
    }
}
