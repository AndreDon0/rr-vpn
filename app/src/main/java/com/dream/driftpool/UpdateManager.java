package com.dream.driftpool;

import android.app.NotificationManager;
import android.app.job.JobScheduler;
import android.content.Context;
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
    static final long INTERVAL = 5L * 60 * 1000;
    static final int JOB = 6201, NOTICE = 2;
    static final String OPEN = "com.dream.driftpool.UPDATE";
    private static final Object LOCK = new Object();
    private UpdateManager() {}

    static void cancelLegacyJob(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB);
        context.getSystemService(NotificationManager.class).cancel(NOTICE);
    }
    static boolean due(Context context) {
        long last = context.getSharedPreferences("updates", 0).getLong("last_check", 0), now = System.currentTimeMillis();
        return last == 0 || now < last || now - last >= INTERVAL;
    }
    static File apk(Context context) { return new File(context.getCacheDir(), "updates/update.apk"); }
    static long downloaded(Context context, UpdateRelease release) {
        return Math.min(release.size(), new File(apk(context).getParentFile(), "download-" + release.sha256() + ".part").length());
    }
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

    interface Progress {
        void transferred(long downloaded, long total);
        default void verifying() { }
    }
    interface ConnectionFactory { HttpURLConnection open(String url, long offset, android.net.Network network) throws Exception; }
    static final class Paused extends java.io.IOException { Paused() { super("Update paused"); } }

    static boolean wifiOnly(Context context) { return context.getSharedPreferences("updates", 0).getBoolean("wifi_only", true); }
    static boolean setWifiOnly(Context context, boolean enabled) {
        return context.getSharedPreferences("updates", 0).edit().putBoolean("wifi_only", enabled).commit();
    }
    static boolean allows(boolean wifiOnly, android.net.NetworkCapabilities capabilities) {
        return capabilities != null && allows(wifiOnly,
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET),
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN));
    }
    static boolean allows(boolean wifiOnly, boolean internet, boolean wifi, boolean vpn) {
        return internet && (!wifiOnly || wifi && !vpn);
    }
    static android.net.Network downloadNetwork(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        android.net.Network active = manager.getActiveNetwork(); boolean onlyWifi = wifiOnly(context);
        if (allows(onlyWifi, manager.getNetworkCapabilities(active))) return active;
        if (onlyWifi) for (android.net.Network network : manager.getAllNetworks())
            if (allows(true, manager.getNetworkCapabilities(network))) return network;
        return null;
    }
    private static void ensureAllowed(Context context, android.net.Network network, java.util.function.BooleanSupplier active) throws Paused {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()
                || !allows(wifiOnly(context), context.getSystemService(ConnectivityManager.class).getNetworkCapabilities(network))) throw new Paused();
    }

    static File download(Context context, UpdateRelease release) throws Exception {
        return download(context, release, (done, total) -> { }, () -> true);
    }
    static File download(Context context, UpdateRelease release, Progress progress, java.util.function.BooleanSupplier active) throws Exception {
        return download(context, release, progress, active, (url, offset, network) -> connect(url, true, offset, network));
    }
    static File download(Context context, UpdateRelease release, Progress progress, java.util.function.BooleanSupplier active,
                         ConnectionFactory connections) throws Exception {
        synchronized (LOCK) {
            if (!newer(context, release)) throw new IllegalArgumentException("Update is not newer or compatible");
            File target = apk(context);
            if (target.isFile()) {
                try { verify(context, target, release); progress.transferred(release.size(), release.size()); return target; }
                catch (Exception ignored) { target.delete(); }
            }
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new java.io.IOException("No update cache");
            File partial = new File(target.getParentFile(), "download-" + release.sha256() + ".part");
            for (File old : target.getParentFile().listFiles())
                if (old.getName().endsWith(".part") && !old.equals(partial)) old.delete();
            if (partial.length() >= release.size()) {
                try {
                    if (partial.length() != release.size()) throw new java.io.IOException("Incomplete update download");
                progress.verifying(); verify(context, partial, release);
                    if (!partial.renameTo(target)) throw new java.io.IOException("Cannot save update");
                    return target;
                } catch (SecurityException e) { partial.delete(); }
            }
            android.net.Network network = downloadNetwork(context);
            if (network == null) throw new Paused();
            ensureAllowed(context, network, active);
            long offset = partial.length();
            HttpURLConnection connection = connections.open(release.url(), offset, network);
            try {
                int response = connection.getResponseCode();
                if (offset > 0 && response == 200) offset = 0; // Server does not support resuming: restart safely.
                else if (response == 206) {
                    String range = connection.getHeaderField("Content-Range");
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range == null ? "" : range);
                    if (!matcher.matches() || Long.parseLong(matcher.group(1)) != offset
                            || Long.parseLong(matcher.group(3)) != release.size()
                            || Long.parseLong(matcher.group(2)) < offset || Long.parseLong(matcher.group(2)) >= release.size())
                        throw new SecurityException("Wrong update byte range");
                } else if (response != 200) throw new java.io.IOException("Update HTTP " + response);
                progress.transferred(offset, release.size());
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial, offset > 0)) {
                    byte[] buffer = new byte[64 * 1024]; long total = offset;
                    while (true) {
                        ensureAllowed(context, network, active);
                        int length = in.read(buffer); if (length == -1) break;
                        ensureAllowed(context, network, active);
                        total += length;
                        if (total > release.size()) throw new SecurityException("Oversized update");
                        out.write(buffer, 0, length); progress.transferred(total, release.size());
                    }
                    out.getFD().sync();
                }
                if (partial.length() != release.size()) throw new java.io.IOException("Incomplete update download");
                progress.verifying(); verify(context, partial, release);
                if (!partial.renameTo(target)) throw new java.io.IOException("Cannot save update");
                return target;
            } catch (SecurityException e) { partial.delete(); throw e; }
            finally { connection.disconnect(); }
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

    private static HttpURLConnection connect(String address, boolean binary) throws Exception {
        return connect(address, binary, 0, null);
    }
    private static HttpURLConnection connect(String address, boolean binary, long offset, android.net.Network network) throws Exception {
        URI uri = URI.create(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || !(host.equals("raw.githubusercontent.com") || host.equals("github.com")
                    || binary && (host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"))))
                throw new SecurityException("Untrusted update host");
            HttpURLConnection connection = (HttpURLConnection) (network == null ? uri.toURL().openConnection() : network.openConnection(uri.toURL()));
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "RR-VPN-Updater"); connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new java.io.IOException("Missing redirect"); uri = uri.resolve(location); continue;
            }
            if (status != 200 && !(binary && status == 206)) { connection.disconnect(); throw new java.io.IOException("Update HTTP " + status); }
            return connection;
        }
        throw new java.io.IOException("Too many redirects");
    }
}
