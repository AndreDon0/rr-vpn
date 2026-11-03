package com.dream.driftpool;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Owns the switch, one atomic three-feed snapshot, and OS-managed update scheduling. */
final class RoutingLists {
    static final long INTERVAL = TimeUnit.HOURS.toMillis(12);
    static final int PERIODIC_JOB = 6101;
    private static final int STALE_JOB = 6102;
    static final String RU4 = "https://www.ipdeny.com/ipblocks/data/aggregated/ru-aggregated.zone";
    static final String RU6 = "https://www.ipdeny.com/ipv6/ipaddresses/aggregated/ru-aggregated.zone";
    static final String ANTIFILTER = "https://antifilter.download/list/allyouneed.lst";
    static volatile RoutingData current;
    static volatile boolean lastRefreshFailed;
    private static volatile Boolean enabled;

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("routing", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) {
        if (enabled == null) enabled = preferences(context).getBoolean("enabled", true);
        return enabled;
    }
    static synchronized void setEnabled(Context context, boolean value) throws Exception {
        if (!preferences(context).edit().putBoolean("enabled", value).commit()) throw new IllegalStateException("Settings write failed");
        enabled = value;
        schedule(context);
    }
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "routing-lists.json"));
    }
    static synchronized RoutingData load(Context context) throws Exception {
        if (current != null) return current;
        lastRefreshFailed = preferences(context).getBoolean("lastRefreshFailed", false);
        try { current = RoutingData.fromJson(new String(file(context).readFully(), StandardCharsets.UTF_8)); }
        catch (Exception cacheFailure) {
            try (InputStream in = context.getAssets().open("routing-lists.json")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                current = RoutingData.fromJson(out.toString("UTF-8"));
            }
        }
        return current;
    }

    static void refresh(Context context) throws Exception {
        refresh(context, SubscriptionFetcher::fetch);
    }
    interface FeedFetcher { String fetch(String address) throws Exception; }
    static void refresh(Context context, FeedFetcher fetcher) throws Exception {
        try {
            // Validate every response before touching the existing snapshot.
            RoutingData next = RoutingData.create(RoutingData.parseList(fetcher.fetch(RU4), 4),
                    RoutingData.parseList(fetcher.fetch(RU6), 6),
                    RoutingData.parseList(fetcher.fetch(ANTIFILTER), 0), System.currentTimeMillis());
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            publish(context, next);
        } catch (Exception e) {
            lastRefreshFailed = true;
            preferences(context).edit().putBoolean("lastRefreshFailed", true).apply();
            throw e;
        }
    }

    static synchronized void publish(Context context, RoutingData next) throws Exception {
        AtomicFile store = file(context);
        FileOutputStream out = null;
        try {
            out = store.startWrite();
            out.write(next.toJson().getBytes(StandardCharsets.UTF_8));
            store.finishWrite(out);
        } catch (Exception e) { if (out != null) store.failWrite(out); throw e; }
        current = next;
        lastRefreshFailed = false;
        preferences(context).edit().putBoolean("lastRefreshFailed", false).apply();
    }

    static void schedule(Context context) throws Exception {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!enabled(context)) {
            scheduler.cancel(PERIODIC_JOB); scheduler.cancel(STALE_JOB); return;
        }
        RoutingData data = load(context);
        ComponentName service = new ComponentName(context, RoutingRefreshJob.class);
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
            JobInfo job = new JobInfo.Builder(PERIODIC_JOB, service).setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresBatteryNotLow(true)
                    .setPeriodic(INTERVAL).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Job scheduling failed");
        }
        if (System.currentTimeMillis() - data.updatedAt() >= INTERVAL && scheduler.getPendingJob(STALE_JOB) == null) {
            JobInfo job = new JobInfo.Builder(STALE_JOB, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(1000).setBackoffCriteria(TimeUnit.HOURS.toMillis(1), JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Job scheduling failed");
        }
    }
}
