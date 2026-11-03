package com.dream.driftpool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** All network/disk work runs off the UI thread. A shared lock serializes job and UI downloads. */
final class UpdateManager {
    static final long INTERVAL = 12L * 60 * 60 * 1000;
    static final int JOB = 6201, NOTICE = 2;
    static final String OPEN = "com.dream.driftpool.UPDATE";
    private static final Object LOCK = new Object();
    private UpdateManager() {}

    static void schedule(Context context) {
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB,
                new ComponentName(context, UpdateJob.class)).setPersisted(true).setPeriodic(INTERVAL)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresBatteryNotLow(true)
                .setBackoffCriteria(3L * 60 * 60 * 1000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    static boolean due(Context context) {
        long last = context.getSharedPreferences("updates", 0).getLong("last_check", 0), now = System.currentTimeMillis();
        return last == 0 || now < last || now - last >= INTERVAL;
    }
    static File apk(Context context) { return new File(context.getCacheDir(), "updates/update.apk"); }
    private static AtomicFile metadata(Context context) { return new AtomicFile(new File(context.getNoBackupFilesDir(), "app-update.json")); }

    static UpdateRelease cached(Context context) {
        synchronized (LOCK) {
            try { return UpdateRelease.parse(new String(metadata(context).readFully(), StandardCharsets.UTF_8)); }
            catch (Exception e) { return null; }
        }
    }
    static boolean newer(Context context, UpdateRelease release) throws Exception {
        return release != null && release.available((int) code(installed(context)), Build.VERSION.SDK_INT);
    }
    static UpdateRelease check(Context context) throws Exception {
        synchronized (LOCK) {
            HttpURLConnection connection = connect(UpdateRelease.FEED, false);
            byte[] body;
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int length; (length = in.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (bytes.size() + length > 64 * 1024) throw new IllegalArgumentException("Oversized update metadata");
                    bytes.write(buffer, 0, length);
                }
                body = bytes.toByteArray();
            } finally { connection.disconnect(); }
            UpdateRelease release = UpdateRelease.parse(new String(body, StandardCharsets.UTF_8));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            AtomicFile file = metadata(context); FileOutputStream out = file.startWrite();
            try { out.write(body); file.finishWrite(out); } catch (Exception e) { file.failWrite(out); throw e; }
            context.getSharedPreferences("updates", 0).edit().putLong("last_check", System.currentTimeMillis()).apply();
            if (!newer(context, release)) {
                apk(context).delete(); context.getSystemService(NotificationManager.class).cancel(NOTICE);
            }
            return release;
        }
    }

    static File download(Context context, UpdateRelease release) throws Exception {
        synchronized (LOCK) {
            if (!newer(context, release)) throw new IllegalArgumentException("Update is not newer or compatible");
            File target = apk(context);
            if (target.isFile()) {
                try { verify(context, target, release); return target; } catch (Exception ignored) { target.delete(); }
            }
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new java.io.IOException("No update cache");
            File partial = new File(target.getParentFile(), "download.part"); partial.delete();
            HttpURLConnection connection = connect(release.url(), true);
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0;
                for (int length; (length = in.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    total += length;
                    if (total > release.size()) throw new java.io.IOException("Oversized update");
                    out.write(buffer, 0, length);
                }
                out.getFD().sync();
            } catch (Exception e) { partial.delete(); throw e; }
            finally { connection.disconnect(); }
            try {
                verify(context, partial, release);
                if (!partial.renameTo(target)) throw new java.io.IOException("Cannot save update");
            } finally { partial.delete(); }
            return target;
        }
    }

    static boolean ready(Context context, UpdateRelease release) {
        synchronized (LOCK) {
            try { verify(context, apk(context), release); return true; } catch (Exception e) { return false; }
        }
    }
    static void verify(Context context, File file, UpdateRelease release) throws Exception {
        if (release == null || !file.isFile() || file.length() != release.size()) throw new SecurityException("Wrong update size");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[64 * 1024];
            for (int read; (read = in.read(bytes)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                digest.update(bytes, 0, read);
            }
        }
        StringBuilder hash = new StringBuilder(); for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!release.sha256().contentEquals(hash)) throw new SecurityException("Wrong update checksum");
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo current = installed(context), candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (candidate == null || !context.getPackageName().equals(candidate.packageName) || code(candidate) != release.versionCode()
                || code(candidate) <= code(current) || !release.versionName().equals(candidate.versionName)
                || candidate.applicationInfo == null || candidate.applicationInfo.minSdkVersion != release.minSdk()
                || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT
                || !signers(current).equals(signers(candidate)) || signers(current).isEmpty())
            throw new SecurityException("Update package, version or signing identity mismatch");
    }
    private static Set<String> signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) Arrays.stream(signatures).forEach(value -> result.add(value.toCharsString()));
        return result;
    }
    private static PackageInfo installed(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(),
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    }
    private static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    static void automatic(Context context) throws Exception {
        UpdateRelease release = due(context) ? check(context) : cached(context);
        if (!newer(context, release)) return;
        boolean unmetered = !context.getSystemService(ConnectivityManager.class).isActiveNetworkMetered();
        if (unmetered) download(context, release);
        notifyUpdate(context, release, ready(context, release));
    }
    private static void notifyUpdate(Context context, UpdateRelease release, boolean ready) {
        Context language = AppLanguage.wrap(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("updates", language.getString(R.string.updates), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 2, new Intent(context, MainActivity.class).setAction(OPEN),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.notify(NOTICE, new Notification.Builder(context, "updates").setSmallIcon(R.drawable.ic_pool)
                    .setContentTitle(language.getString(R.string.update_available, release.versionName()))
                    .setContentText(language.getString(ready ? R.string.update_ready : R.string.update_available_hint))
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build());
        } catch (SecurityException ignored) { /* The update remains visible in settings if notifications are denied. */ }
    }

    private static HttpURLConnection connect(String address, boolean binary) throws Exception {
        URI uri = URI.create(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || !(host.equals("raw.githubusercontent.com") || host.equals("github.com")
                    || binary && (host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"))))
                throw new SecurityException("Untrusted update host");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "RR-VPN-Updater"); connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status >= 300 && status <= 399) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new java.io.IOException("Missing redirect"); uri = uri.resolve(location); continue;
            }
            if (status != 200) { connection.disconnect(); throw new java.io.IOException("Update HTTP " + status); }
            return connection;
        }
        throw new java.io.IOException("Too many redirects");
    }
}
