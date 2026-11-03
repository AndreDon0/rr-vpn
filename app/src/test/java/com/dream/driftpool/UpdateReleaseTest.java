package com.dream.driftpool;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateReleaseTest {
    private JSONObject feed() throws Exception {
        return new JSONObject().put("schema", 1).put("versionCode", 7).put("versionName", "0.5.1")
                .put("minSdk", 26).put("size", 63_000_000).put("sha256", "a".repeat(64))
                .put("url", UpdateRelease.RELEASES + "v0.5.1/RR-VPN-0.5.1-universal.apk");
    }
    @Test public void newerCompatibleUniversalRoundTrips() throws Exception {
        UpdateRelease release = UpdateRelease.parse(feed().toString());
        assertTrue(release.available(6, 26)); assertFalse(release.available(7, 35)); assertFalse(release.available(8, 35));
        assertFalse(release.available(6, 25)); assertEquals(release, UpdateRelease.parse(release.json()));
    }
    @Test public void rejectsForeignHostsReposAndNonUniversalPackages() throws Exception {
        for (String url : new String[]{"http://github.com/AndreDon0/rr-vpn/releases/download/v0.5.1/RR-VPN-0.5.1-universal.apk",
                "https://evil.example/RR-VPN-0.5.1-universal.apk", UpdateRelease.RELEASES + "v0.5.1/RR-VPN-0.5.1-arm64.apk",
                UpdateRelease.RELEASES.replace("AndreDon0", "someone") + "v0.5.1/RR-VPN-0.5.1-universal.apk",
                UpdateRelease.RELEASES + "v0.5.1/RR-VPN-0.5.1-universal.apk?redirect=evil"}) {
            rejected(feed().put("url", url));
        }
    }
    @Test public void rejectsMalformedHashSizesSchemaAndVersions() throws Exception {
        rejected(feed().put("sha256", "a".repeat(63))); rejected(feed().put("sha256", "G".repeat(64)));
        rejected(feed().put("size", 0)); rejected(feed().put("size", UpdateRelease.MAX_APK_BYTES + 1));
        rejected(feed().put("versionCode", 0)); rejected(feed().put("versionCode", (long) Integer.MAX_VALUE + 1));
        rejected(feed().put("versionName", "../other")); rejected(feed().put("schema", 2)); rejected(feed().put("minSdk", 25));
    }
    private void rejected(JSONObject value) throws Exception {
        try { UpdateRelease.parse(value.toString()); fail("Bad metadata accepted"); } catch (IllegalArgumentException expected) { }
    }
}
