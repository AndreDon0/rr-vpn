package com.dream.driftpool;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public class UpdateTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private android.os.LocaleList savedNativeLanguages;
    private String savedLanguage;
    private boolean hadMigration, savedMigration;
    @org.junit.Before public void saveLanguage() {
        android.content.SharedPreferences preferences=context.getSharedPreferences("appearance",0);
        savedLanguage=preferences.getString("language",null);
        hadMigration=preferences.contains("native_language_migrated"); savedMigration=preferences.getBoolean("native_language_migrated",false);
        if (android.os.Build.VERSION.SDK_INT>=33) savedNativeLanguages=context.getSystemService(android.app.LocaleManager.class).getApplicationLocales();
    }
    @org.junit.After public void restoreLanguage() {
        android.content.SharedPreferences.Editor edit=context.getSharedPreferences("appearance",0).edit();
        if (savedLanguage==null) edit.remove("language"); else edit.putString("language",savedLanguage);
        if (hadMigration) edit.putBoolean("native_language_migrated",savedMigration); else edit.remove("native_language_migrated");
        edit.commit();
        if (android.os.Build.VERSION.SDK_INT>=33) context.getSystemService(android.app.LocaleManager.class).setApplicationLocales(savedNativeLanguages);
    }
    private UpdateRelease release(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; for (int n; (n = in.read(bytes)) != -1;) digest.update(bytes, 0, n);
        }
        StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getPath(), 0);
        assertNotNull(info);
        return new UpdateRelease((android.os.Build.VERSION.SDK_INT>=28 ? (int)info.getLongVersionCode() : info.versionCode), info.versionName, info.applicationInfo.minSdkVersion,
                file.length(), hash.toString(), UpdateRelease.RELEASES + "v" + info.versionName + "/RR-VPN-" + info.versionName + "-universal.apk");
    }
    @Test public void acceptsNewSignedApkRejectsHashVersionAndDifferentSigner() throws Exception {
        File candidate = new File(context.getFilesDir(), "candidate.apk");
        org.junit.Assume.assumeTrue(candidate.isFile());
        UpdateRelease release = release(candidate); UpdateManager.verify(context, candidate, release);
        rejected(candidate, new UpdateRelease(release.versionCode(), release.versionName(), release.minSdk(), release.size(), "0".repeat(64), release.url()));
        rejected(candidate, new UpdateRelease(release.versionCode()-1, release.versionName(), release.minSdk(), release.size(), release.sha256(), release.url()));
        rejected(candidate, new UpdateRelease(release.versionCode(), release.versionName(), release.minSdk()+1, release.size(), release.sha256(), release.url()));
        File other = new File(context.getFilesDir(), "other-signer.apk"); assertTrue(other.isFile());
        rejected(other, release(other));
        File target = UpdateManager.apk(context); target.getParentFile().mkdirs();
        java.nio.file.Files.copy(candidate.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(UpdateManager.ready(context, release));
        try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(UpdateProvider.uri(context), "r")) {
            assertNotNull(fd); assertEquals(candidate.length(), fd.getStatSize());
        }
        try { context.getContentResolver().openFileDescriptor(UpdateProvider.uri(context), "w"); fail("Writable update provider"); }
        catch (java.io.FileNotFoundException expected) { }
        try { context.getContentResolver().openFileDescriptor(android.net.Uri.parse("content://" + context.getPackageName() + ".updates/../subscription.enc"), "r"); fail("Path traversal accepted"); }
        catch (java.io.FileNotFoundException expected) { }
        target.delete();
    }
    @Test public void persistedJobProviderAndInstallPermissionAreScoped() throws Exception {
        UpdateManager.cancelLegacyJob(context);
        assertNull(context.getSystemService(android.app.job.JobScheduler.class).getPendingJob(UpdateManager.JOB));
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(context.getPackageName() + ".updates", 0);
        assertNotNull(provider); assertFalse(provider.exported); assertTrue(provider.grantUriPermissions);
        assertTrue(java.util.Arrays.asList(context.getPackageManager().getPackageInfo(context.getPackageName(),
                android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions)
                .contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        Intent installer = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(UpdateProvider.uri(context)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertNotNull(context.getPackageManager().resolveActivity(installer, 0));
    }
    @Test public void wifiPolicyDefaultsOnAndDistinguishesCellularMeteredWifiAndVpn() throws Exception {
        android.content.SharedPreferences preferences = context.getSharedPreferences("updates", 0);
        boolean had = preferences.contains("wifi_only"), preceding = UpdateManager.wifiOnly(context);
        try {
            preferences.edit().remove("wifi_only").commit(); assertTrue(UpdateManager.wifiOnly(context));
            assertTrue(UpdateManager.setWifiOnly(context, false)); assertFalse(UpdateManager.wifiOnly(context));
            // Metering is deliberately absent: Wi-Fi may be metered, cellular may be unmetered.
            assertTrue(UpdateManager.allows(true, true, true, false));
            assertFalse(UpdateManager.allows(true, true, false, false));
            assertTrue(UpdateManager.allows(false, true, false, false));
            assertFalse(UpdateManager.allows(true, true, true, true));
            assertFalse(UpdateManager.allows(false, false, true, false));
            assertFalse(UpdateManager.allows(false, null));
        } finally {
            if (had) UpdateManager.setWifiOnly(context, preceding); else preferences.edit().remove("wifi_only").commit();
        }
    }

    @Test public void realCellularNetworkIsBlockedUntilWifiOnlyIsDisabled() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("no_wifi")));
        boolean preceding = UpdateManager.wifiOnly(context); UpdateManager.setWifiOnly(context, true);
        try {
            long end = System.currentTimeMillis()+15000;
            while (System.currentTimeMillis()<end && UpdateManager.downloadNetwork(context)!=null) Thread.sleep(100);
            assertNull("Wi-Fi-only mode must not pick cellular", UpdateManager.downloadNetwork(context));
            UpdateManager.setWifiOnly(context, false);
            android.net.Network network = UpdateManager.downloadNetwork(context); assertNotNull(network);
            android.net.NetworkCapabilities caps = context.getSystemService(android.net.ConnectivityManager.class).getNetworkCapabilities(network);
            assertTrue(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR));
        } finally { UpdateManager.setWifiOnly(context, preceding); }
    }

    @Test public void interruptedDownloadsResumeAndServersIgnoringRangesRestartSafely() throws Exception {
        File candidate = new File(context.getFilesDir(), "candidate.apk"); org.junit.Assume.assumeTrue(candidate.isFile());
        UpdateRelease release = release(candidate); boolean wifi = UpdateManager.wifiOnly(context);
        UpdateManager.setWifiOnly(context, false); UpdateManager.apk(context).delete();
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.List<Long> offsets = new java.util.ArrayList<>();
        UpdateManager.ConnectionFactory source = (url, offset, network) -> { offsets.add(offset); return connection(candidate, offset, true); };
        try {
            try {
                UpdateManager.download(context, release, (done,total) -> { if (done >= 131072) active.set(false); }, active::get, source);
                fail("Background transfer did not pause");
            } catch (UpdateManager.Paused expected) { }
            File partial = new File(UpdateManager.apk(context).getParentFile(), "download-" + release.sha256() + ".part");
            long saved = partial.length(); assertTrue(saved >= 131072 && saved < release.size());
            active.set(true); UpdateManager.download(context, release, (done,total) -> { }, active::get, source);
            assertEquals(java.util.List.of(0L, saved), offsets); assertTrue(UpdateManager.ready(context, release)); assertFalse(partial.exists());
            UpdateManager.apk(context).delete();
            try (java.io.OutputStream out = new java.io.FileOutputStream(partial); java.io.InputStream in = new java.io.FileInputStream(candidate)) {
                byte[] prefix = new byte[8192]; assertEquals(prefix.length, in.read(prefix)); out.write(prefix);
            }
            UpdateManager.download(context, release, (done,total) -> { }, () -> true,
                    (url, offset, network) -> { assertEquals(8192, offset); return connection(candidate, offset, false); });
            assertTrue(UpdateManager.ready(context, release));
        } finally { UpdateManager.apk(context).delete(); UpdateManager.setWifiOnly(context, wifi); }
    }

    private java.net.HttpURLConnection connection(File file, long offset, boolean ranges) throws Exception {
        return new java.net.HttpURLConnection(new java.net.URL("https://github.com/fixture")) {
            @Override public int getResponseCode() { return ranges && offset > 0 ? 206 : 200; }
            @Override public String getHeaderField(String name) { return name.equals("Content-Range") ? "bytes " + offset + "-" + (file.length()-1) + "/" + file.length() : null; }
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                java.io.FileInputStream input = new java.io.FileInputStream(file);
                if (ranges) { long skipped = input.skip(offset); if (skipped != offset) throw new java.io.IOException(); }
                return input;
            }
            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
        };
    }

    @Test public void cornerProgressReadySheetAndWifiSettingSurviveRecreationInBothLanguages() throws Exception {
        File candidate = new File(context.getFilesDir(), "candidate.apk"); org.junit.Assume.assumeTrue(candidate.isFile());
        UpdateRelease base = release(candidate);
        UpdateRelease release = new UpdateRelease(base.versionCode(), base.versionName(), base.minSdk(), base.size(), base.sha256(), base.url(),
                "- Circular update progress.\n- Wi-Fi-only downloads.", "- Круговой индикатор обновления.\n- Загрузка только по Wi-Fi.");
        android.util.AtomicFile metadata = new android.util.AtomicFile(new File(context.getNoBackupFilesDir(), "app-update.json"));
        byte[] preceding = null; try { preceding = metadata.readFully(); } catch (Exception ignored) { }
        String language = context.getSharedPreferences("appearance", 0).getString("language", null);
        android.content.SharedPreferences guide = context.getSharedPreferences("MainActivity", 0);
        boolean had = guide.contains("tutorial_seen_v1"), seen = guide.getBoolean("tutorial_seen_v1", false), wifi = UpdateManager.wifiOnly(context);
        guide.edit().putBoolean("tutorial_seen_v1", true).commit(); AppLanguage.save(context, "en"); UpdateManager.setWifiOnly(context, true);
        context.getSharedPreferences("updates",0).edit().putLong("last_check",System.currentTimeMillis()).commit();
        try (java.io.FileOutputStream out = metadata.startWrite()) { out.write(release.json().getBytes(java.nio.charset.StandardCharsets.UTF_8)); metadata.finishWrite(out); }
        File target = UpdateManager.apk(context); target.getParentFile().mkdirs();
        java.nio.file.Files.copy(candidate.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        UpdateSession session = UpdateSession.get(context);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitReady(session);
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field foreground = UpdateSession.class.getDeclaredField("foreground"); foreground.setAccessible(true); foreground.set(session, false);
                    java.lang.reflect.Method publish = UpdateSession.class.getDeclaredMethod("publish", UpdateSession.Snapshot.class); publish.setAccessible(true);
                    publish.invoke(session, new UpdateSession.Snapshot(release, UpdateSession.Phase.DOWNLOADING, release.size()*42/100, release.size()));
                } catch (Exception e) { throw new AssertionError(e); }
            });
            Thread.sleep(300); onView(withId(R.id.update_button)).check(matches(isDisplayed())); saveScreenshot("rr-vpn-corner-downloading.png");
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method publish = UpdateSession.class.getDeclaredMethod("publish", UpdateSession.Snapshot.class); publish.setAccessible(true);
                    publish.invoke(session, new UpdateSession.Snapshot(release, UpdateSession.Phase.READY, release.size(), release.size()));
                } catch (Exception e) { throw new AssertionError(e); }
            });
            Thread.sleep(200);
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field animation = UpdateButton.class.getDeclaredField("animation"); animation.setAccessible(true);
                    android.animation.ValueAnimator animator = (android.animation.ValueAnimator) animation.get(activity.findViewById(R.id.update_button));
                    assertTrue("Ready ring must sit still", animator == null || !animator.isRunning());
                } catch (Exception e) { throw new AssertionError(e); }
            });
            saveScreenshot("rr-vpn-corner-ready.png");
            onView(withId(R.id.update_button)).perform(click());
            onView(withText(R.string.whats_new)).check(matches(isDisplayed()));
            onView(withText("RR VPN " + release.versionName())).check(matches(isDisplayed()));
            onView(withId(R.id.install_update_button)).check(matches(isEnabled()));
            onView(withSubstring("12 hours")).check(doesNotExist()); onView(withSubstring("approve installation")).check(doesNotExist());
            saveScreenshot("rr-vpn-update-sheet-en.png"); onView(withText(R.string.later)).perform(click());
            onView(withId(R.id.settings_button)).perform(click()); onView(withId(R.id.update_wifi_only)).perform(scrollTo());
            onView(withId(R.id.update_wifi_only)).check(matches(isChecked())).perform(click());
            assertFalse(UpdateManager.wifiOnly(context)); scenario.recreate(); waitReady(session);
            onView(withId(R.id.update_wifi_only)).perform(scrollTo()).check(matches(isNotChecked()));
            saveScreenshot("rr-vpn-settings-updates.png"); onView(withId(R.id.close_settings)).perform(click());
            AppLanguage.save(context, "ru");
            if (android.os.Build.VERSION.SDK_INT<33) scenario.recreate();
            long localeDeadline=System.currentTimeMillis()+10000;
            java.util.concurrent.atomic.AtomicBoolean russian=new java.util.concurrent.atomic.AtomicBoolean();
            while (System.currentTimeMillis()<localeDeadline && !russian.get()) {
                scenario.onActivity(activity -> russian.set("ru".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage())));
                if (!russian.get()) Thread.sleep(50);
            }
            assertTrue(russian.get()); waitReady(session);
            onView(withId(R.id.update_button)).perform(click());
            onView(withText("Что нового")).check(matches(isDisplayed()));
            saveScreenshot("rr-vpn-update-sheet-ru.png");
        } finally {
            java.lang.reflect.Field foreground = UpdateSession.class.getDeclaredField("foreground"); foreground.setAccessible(true);
            foreground.set(session, false);
            java.lang.reflect.Field worker = UpdateSession.class.getDeclaredField("io"); worker.setAccessible(true);
            ((java.util.concurrent.ExecutorService) worker.get(session)).submit(() -> { }).get(35, java.util.concurrent.TimeUnit.SECONDS);
            java.lang.reflect.Field state = UpdateSession.class.getDeclaredField("snapshot"); state.setAccessible(true);
            state.set(session, new UpdateSession.Snapshot(null, UpdateSession.Phase.IDLE,0,0));
            UpdateManager.apk(context).delete();
            if (preceding == null) metadata.delete(); else { java.io.FileOutputStream out = metadata.startWrite(); out.write(preceding); metadata.finishWrite(out); }
            android.content.SharedPreferences.Editor edit = context.getSharedPreferences("appearance",0).edit();
            if (language == null) edit.remove("language"); else edit.putString("language",language); edit.commit();
            UpdateManager.setWifiOnly(context,wifi);
            if (had) guide.edit().putBoolean("tutorial_seen_v1",seen).commit(); else guide.edit().remove("tutorial_seen_v1").commit();
        }
    }

    private void waitReady(UpdateSession session) throws Exception { waitReady(session,30000); }
    private void waitReady(UpdateSession session, long timeout) throws Exception {
        long deadline = System.currentTimeMillis()+timeout;
        while (System.currentTimeMillis()<deadline) { if (session.snapshot().phase()==UpdateSession.Phase.READY) return; Thread.sleep(50); }
        fail("Update not ready: " + session.snapshot().phase());
    }

    @Test public void liveReleaseAutomaticallyDownloadsAndOpensAndroidInstaller() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("live_updates")));
        String preceding = context.getSharedPreferences("appearance", 0).getString("language", null);
        android.content.SharedPreferences guide = context.getSharedPreferences("MainActivity", 0);
        boolean had = guide.contains("tutorial_seen_v1"), seen = guide.getBoolean("tutorial_seen_v1", false);
        guide.edit().putBoolean("tutorial_seen_v1", true).commit(); AppLanguage.save(context, "en");
        try {
            UpdateRelease release = UpdateManager.check(context);
            assertTrue("Install a temporary lower-version updater build for this opt-in test", UpdateManager.newer(context, release));
            assertFalse(context.getSystemService(android.net.ConnectivityManager.class).isActiveNetworkMetered());
            if (!"true".equals(InstrumentationRegistry.getArguments().getString("reuse_partial"))) {
                UpdateManager.apk(context).delete();
                File[] partials = UpdateManager.apk(context).getParentFile().listFiles();
                if (partials != null) for (File partial : partials) if (partial.getName().endsWith(".part")) partial.delete();
            }
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                UpdateSession session = UpdateSession.get(context);
                boolean progressSeen = false; long transferDeadline = System.currentTimeMillis()+300000;
                while (System.currentTimeMillis()<transferDeadline) {
                    UpdateSession.Snapshot state = session.snapshot();
                    if (state.phase()==UpdateSession.Phase.DOWNLOADING && state.downloaded()>0 && state.downloaded()<state.total()) {
                        progressSeen = true; scenario.recreate();
                        assertSame(session,UpdateSession.get(context)); saveScreenshot("rr-vpn-live-progress.png"); break;
                    }
                    if (state.phase()==UpdateSession.Phase.READY || state.phase()==UpdateSession.Phase.ERROR) break;
                    Thread.sleep(50);
                }
                assertTrue("Live download did not report byte progress",progressSeen); waitReady(session,300000);
                assertTrue(UpdateManager.ready(context, release));
                onView(withId(R.id.update_button)).perform(click());
                long deadline = System.currentTimeMillis() + 30000;
                java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
                while (System.currentTimeMillis() < deadline && !ready.get()) {
                    scenario.onActivity(activity -> {
                        try {
                            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("updateDialog"); field.setAccessible(true);
                            android.app.Dialog dialog = (android.app.Dialog) field.get(activity);
                            ready.set(dialog != null && dialog.findViewById(R.id.install_update_button).isEnabled());
                        } catch (Exception e) { throw new AssertionError(e); }
                    });
                    if (!ready.get()) Thread.sleep(100);
                }
                assertTrue("Verified update not offered", ready.get());
                saveScreenshot("rr-vpn-update-ready.png");
                assertTrue(context.getPackageManager().canRequestPackageInstalls());
                onView(withId(R.id.install_update_button)).perform(click());
                String installer = ""; boolean confirmation = false; deadline = System.currentTimeMillis() + 45000;
                while (System.currentTimeMillis() < deadline) {
                    android.view.accessibility.AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                            .getUiAutomation().getRootInActiveWindow();
                    if (root != null && root.getPackageName() != null) installer = root.getPackageName().toString();
                    if (installer.contains("packageinstaller") && root != null) {
                        for (String label : new String[]{"Update", "Install"})
                            for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
                                if (node.getText() != null && label.equalsIgnoreCase(node.getText().toString())) confirmation = true;
                    }
                    if (confirmation) break;
                    Thread.sleep(100);
                }
                assertTrue("Android did not offer the installation confirmation", confirmation);
                saveScreenshot("rr-vpn-android-installer.png");
                // Leave the system confirmation unaccepted; Espresso cannot control an external activity.
            }
        } finally {
            android.content.SharedPreferences.Editor edit = context.getSharedPreferences("appearance", 0).edit();
            if (preceding == null) edit.remove("language"); else edit.putString("language", preceding); edit.commit();
            if (had) guide.edit().putBoolean("tutorial_seen_v1", seen).commit(); else guide.edit().remove("tutorial_seen_v1").commit();
        }
    }
    private void saveScreenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (java.io.FileOutputStream out = context.openFileOutput(name, 0)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }

    private void rejected(File file, UpdateRelease release) throws Exception {
        try { UpdateManager.verify(context, file, release); fail("Invalid update accepted"); } catch (SecurityException expected) { }
    }
}
