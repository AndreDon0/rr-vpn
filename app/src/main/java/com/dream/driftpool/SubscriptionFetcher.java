package com.dream.driftpool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class SubscriptionFetcher {
    static String fetch(String address) throws Exception {
        URI uri = new URI(address);
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("HTTPS subscription required");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "v2rayNG/DriftPool-0.1");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IllegalStateException("Missing redirect");
                    uri = uri.resolve(next);
                    continue;
                }
                if (code != 200) throw new IllegalStateException("Subscription request failed");
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        if (out.size() + n > Subscription.MAX_BYTES) throw new IllegalArgumentException("Subscription too large");
                        out.write(buffer, 0, n);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally { connection.disconnect(); }
        }
        throw new IllegalStateException("Too many redirects");
    }
}
