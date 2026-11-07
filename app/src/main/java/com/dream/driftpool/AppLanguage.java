package com.dream.driftpool;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/** Android 13 app-language settings are authoritative; older phones use the local picker. */
final class AppLanguage {
    private AppLanguage() {}
    private static boolean supported(String language) { return language.equals("ru") || language.equals("en"); }
    static String firstSupported(LocaleList locales) {
        for (int i = 0; i < locales.size(); i++) if (supported(locales.get(i).getLanguage())) return locales.get(i).getLanguage();
        return "";
    }
    static String preferred(LocaleList locales) {
        String result = firstSupported(locales); return result.isEmpty() ? "en" : result;
    }
    static String choice(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) {
            String nativeChoice = firstSupported(context.getSystemService(LocaleManager.class).getApplicationLocales());
            if (!nativeChoice.isEmpty()) return nativeChoice;
            if (preferences.getBoolean("native_language_migrated", false)) return "system";
        }
        String saved = preferences.getString("language", "system"); return supported(saved) ? saved : "system";
    }
    static String selected(Context context) {
        String explicit = choice(context);
        if (supported(explicit)) return explicit;
        LocaleList system = Build.VERSION.SDK_INT >= 33 ? context.getSystemService(LocaleManager.class).getSystemLocales()
                : Resources.getSystem().getConfiguration().getLocales();
        return preferred(system);
    }
    static void migrate(Context context) {
        if (Build.VERSION.SDK_INT < 33) return;
        android.content.SharedPreferences preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        if (preferences.getBoolean("native_language_migrated", false)) return;
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        String saved = preferences.getString("language", "system");
        if (manager.getApplicationLocales().isEmpty() && supported(saved))
            manager.setApplicationLocales(LocaleList.forLanguageTags(saved));
        preferences.edit().putBoolean("native_language_migrated", true).commit();
    }
    static boolean save(Context context, String language) {
        if (!supported(language) && !language.equals("system")) throw new IllegalArgumentException("Unsupported language");
        android.content.SharedPreferences preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        if (!preferences.edit().putString("language", language).putBoolean("native_language_migrated", true).commit()) return false;
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager.class).setApplicationLocales(
                language.equals("system") ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(language));
        PoolVpnService.settingsChanged();
        return true;
    }
    static Context wrap(Context context) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        Locale locale = Locale.forLanguageTag(selected(context)); configuration.setLocales(new LocaleList(locale)); configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }
}
