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
    private UpdateRelease release(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; for (int n; (n = in.read(bytes)) != -1;) digest.update(bytes, 0, n);
        }
        StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
        return new UpdateRelease(7, "0.5.1", 26, file.length(), hash.toString(), UpdateRelease.RELEASES + "v0.5.1/RR-VPN-0.5.1-universal.apk");
    }
    @Test public void acceptsNewSignedApkRejectsHashVersionAndDifferentSigner() throws Exception {
        File candidate = new File(context.getFilesDir(), "candidate.apk");
        org.junit.Assume.assumeTrue(candidate.isFile());
        UpdateRelease release = release(candidate); UpdateManager.verify(context, candidate, release);
        rejected(candidate, new UpdateRelease(7, "0.5.1", 26, release.size(), "0".repeat(64), release.url()));
        rejected(candidate, new UpdateRelease(6, "0.5.1", 26, release.size(), release.sha256(), release.url()));
        rejected(candidate, new UpdateRelease(7, "0.5.1", 27, release.size(), release.sha256(), release.url()));
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
        UpdateManager.schedule(context);
        android.app.job.JobInfo job = context.getSystemService(android.app.job.JobScheduler.class).getPendingJob(UpdateManager.JOB);
        assertNotNull(job); assertTrue(job.isPersisted()); assertEquals(UpdateManager.INTERVAL, job.getIntervalMillis());
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(context.getPackageName() + ".updates", 0);
        assertNotNull(provider); assertFalse(provider.exported); assertTrue(provider.grantUriPermissions);
        assertTrue(java.util.Arrays.asList(context.getPackageManager().getPackageInfo(context.getPackageName(),
                android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions)
                .contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        Intent installer = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(UpdateProvider.uri(context)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertNotNull(context.getPackageManager().resolveActivity(installer, 0));
    }
    @Test public void updatesEntryOpensEnglishAndRussianDialog() throws Exception {
        String preceding = context.getSharedPreferences("appearance", 0).getString("language", null);
        android.content.SharedPreferences guide = context.getSharedPreferences("MainActivity", 0);
        boolean had = guide.contains("tutorial_seen_v1"), seen = guide.getBoolean("tutorial_seen_v1", false);
        guide.edit().putBoolean("tutorial_seen_v1", true).commit();
        try {
            for (String language : new String[]{"en", "ru"}) {
                AppLanguage.save(context, language);
                try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                    onView(withId(R.id.settings_button)).perform(click());
                    onView(withId(R.id.updates_button)).perform(scrollTo(), click());
                    onView(withText(AppLanguage.wrap(context).getString(R.string.updates))).check(matches(isDisplayed()));
                    Thread.sleep(2500);
                    android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                    try (java.io.FileOutputStream out = context.openFileOutput("rr-vpn-updates-" + language + ".png", 0)) {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    } finally { bitmap.recycle(); }
                    onView(withId(android.R.id.button2)).perform(click());
                }
            }
        } finally {
            android.content.SharedPreferences.Editor edit = context.getSharedPreferences("appearance", 0).edit();
            if (preceding == null) edit.remove("language"); else edit.putString("language", preceding); edit.commit();
            if (had) guide.edit().putBoolean("tutorial_seen_v1", seen).commit(); else guide.edit().remove("tutorial_seen_v1").commit();
        }
    }
    private void rejected(File file, UpdateRelease release) throws Exception {
        try { UpdateManager.verify(context, file, release); fail("Invalid update accepted"); } catch (SecurityException expected) { }
    }
}
