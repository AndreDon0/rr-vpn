package com.dream.driftpool;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class AppRouting {
    private static final Pattern RUSSIAN_LETTER = Pattern.compile("[А-Яа-яЁё]");
    private static final String RUSTORE = "ru.vk.store";
    // Play listings with a Russian address under “About the developer”. See README.
    private static final Set<String> RUSSIAN_PLAY_APPS = Set.of(
            "com.wildberries.ru",
            "ru.cian.main",
            "ru.dublgis.dgismobile",
            "ru.dublgis.dgismobile4preview",
            "ru.hh.android",
            "ru.ozon.app.android");

    private AppRouting() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("app_routing", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return preferences(context).getBoolean("enabled", true);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        PoolVpnService.settingsChanged();
    }

    static Set<String> excludedPackages(Context context) {
        PackageManager packages = context.getPackageManager();
        Map<String, Integer> matches = new HashMap<>();
        Set<Integer> routedUids = new HashSet<>();
        for (ApplicationInfo app : packages.getInstalledApplications(0)) {
            if (matches(packages, app)) matches.put(app.packageName, app.uid);
            else routedUids.add(app.uid);
        }
        // Android excludes by UID. Keep a shared UID in the VPN if any of its apps needs IP routing.
        matches.values().removeAll(routedUids);
        return matches.keySet();
    }

    private static boolean matches(PackageManager packages, ApplicationInfo app) {
        if (RUSSIAN_LETTER.matcher(app.loadLabel(packages)).find()) return true;
        if (installedByRuStore(packages, app.packageName)) return true;
        return RUSSIAN_PLAY_APPS.contains(app.packageName);
    }

    @SuppressWarnings("deprecation")
    private static boolean installedByRuStore(PackageManager packages, String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo source = packages.getInstallSourceInfo(packageName);
                return RUSTORE.equals(source.getInstallingPackageName()) || RUSTORE.equals(source.getInitiatingPackageName());
            }
            return RUSTORE.equals(packages.getInstallerPackageName(packageName));
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false; // The app may have been uninstalled while building the tunnel.
        }
    }
}
