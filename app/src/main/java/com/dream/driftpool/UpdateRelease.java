package com.dream.driftpool;

import org.json.JSONObject;

/** The public update feed describes exactly one universal APK. No credentials or arbitrary URLs. */
record UpdateRelease(int versionCode, String versionName, int minSdk, long size, String sha256, String url, String notesEn, String notesRu) {
    static final String REPOSITORY = "AndreDon0/rr-vpn";
    static final String FEED = "https://raw.githubusercontent.com/" + REPOSITORY + "/main/updates/stable.json";
    static final String RELEASES = "https://github.com/" + REPOSITORY + "/releases/download/";
    static final long MAX_APK_BYTES = 100L * 1024 * 1024;

    UpdateRelease(int versionCode, String versionName, int minSdk, long size, String sha256, String url) {
        this(versionCode, versionName, minSdk, size, sha256, url, "", "");
    }
    String notes(String language) { return language.equals("ru") && !notesRu.isBlank() ? notesRu : notesEn; }

    static UpdateRelease parse(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        if (value.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported update schema");
        long code = value.getLong("versionCode"), size = value.getLong("size");
        int minSdk = value.getInt("minSdk");
        String version = value.getString("versionName"), hash = value.getString("sha256"), url = value.getString("url");
        if (code <= 0 || code > Integer.MAX_VALUE || size <= 0 || size > MAX_APK_BYTES || minSdk < 26 || minSdk > 1000
                || !version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}") || !hash.matches("[0-9a-f]{64}")
                || !url.equals(RELEASES + "v" + version + "/RR-VPN-" + version + "-universal.apk"))
            throw new IllegalArgumentException("Invalid update metadata");
        JSONObject notes = value.optJSONObject("notes");
        String en = notes == null ? "" : notes.optString("en", ""), ru = notes == null ? "" : notes.optString("ru", "");
        if (en.length() > 4000 || ru.length() > 4000) throw new IllegalArgumentException("Oversized release notes");
        return new UpdateRelease((int) code, version, minSdk, size, hash, url, en, ru);
    }

    String json() throws Exception {
        return new JSONObject().put("schema", 1).put("versionCode", versionCode).put("versionName", versionName)
                .put("minSdk", minSdk).put("size", size).put("sha256", sha256).put("url", url)
                .put("notes", new JSONObject().put("en", notesEn).put("ru", notesRu)).toString();
    }

    boolean available(int installedCode, int sdk) { return versionCode > installedCode && minSdk <= sdk; }
}
