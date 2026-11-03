package com.dream.driftpool;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

/** A small, app-local language override; never changes the phone's locale. */
final class AppLanguage {
    private AppLanguage() {}

    static String selected(Context context) {
        String saved = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "");
        if (saved.equals("ru") || saved.equals("en")) return saved;
        return context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? "ru" : "en";
    }

    static boolean save(Context context, String language) {
        if (!language.equals("ru") && !language.equals("en")) throw new IllegalArgumentException("Unsupported language");
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", language).commit();
    }

    static Context wrap(Context context) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        Locale locale = Locale.forLanguageTag(selected(context));
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }
}
