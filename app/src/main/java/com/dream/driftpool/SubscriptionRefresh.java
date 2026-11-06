package com.dream.driftpool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bounded downloads; failed sources retain their preceding data, successful sources merge atomically. */
final class SubscriptionRefresh {
    interface Fetcher { SubscriptionFetcher.Response fetch(String url) throws Exception; }
    interface UsageFetcher { SubscriptionUsage fetch(String url) throws Exception; }
    record UsageUpdate(String id, String url, SubscriptionUsage usage, long checkedAt) {}
    record Result(SubscriptionBook book, int succeeded, int failed) {}
    record Download(String url, SubscriptionFetcher.Response response) {}
    static Result run(SubscriptionBook previous, List<String> urls, Fetcher fetcher) throws Exception {
        List<String> unique = List.copyOf(new LinkedHashSet<>(urls));
        if (unique.size() > SubscriptionBook.MAX_SOURCES) throw new IllegalArgumentException("Too many subscriptions");
        ExecutorService downloads = Executors.newFixedThreadPool(4);
        List<Future<Download>> tasks = new ArrayList<>();
        try {
            for (String url : unique) tasks.add(downloads.submit(() -> {
                try { SubscriptionBook.validateUrl(url); return new Download(url, fetcher.fetch(url)); }
                catch (Exception e) { return new Download(url, null); }
            }));
            SubscriptionBook book = previous; int succeeded = 0, failed = 0;
            for (Future<Download> task : tasks) {
                Download result = task.get();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    if (result.response() == null) throw new IllegalArgumentException("Fetch failed");
                    book = book.upsert(result.url(), result.response().body(), System.currentTimeMillis(), result.response().usage()); succeeded++;
                } catch (Exception e) { failed++; }
            }
            return new Result(book, succeeded, failed);
        } finally {
            for (Future<Download> task : tasks) task.cancel(true);
            downloads.shutdownNow();
        }
    }

    static List<UsageUpdate> usage(List<SubscriptionBook.Source> sources, UsageFetcher fetcher) throws Exception {
        ExecutorService downloads = Executors.newFixedThreadPool(4);
        List<Future<UsageUpdate>> tasks = new ArrayList<>(); List<UsageUpdate> updates = new ArrayList<>();
        try {
            for (SubscriptionBook.Source source : sources) tasks.add(downloads.submit(() -> {
                long checked = System.currentTimeMillis();
                try { return new UsageUpdate(source.id(), source.url(), fetcher.fetch(source.url()), checked); }
                catch (Exception e) { return null; }
            }));
            for (Future<UsageUpdate> task : tasks) {
                UsageUpdate update = task.get();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (update != null) updates.add(update);
            }
            return updates;
        } finally {
            for (Future<UsageUpdate> task : tasks) task.cancel(true);
            downloads.shutdownNow();
        }
    }
}
