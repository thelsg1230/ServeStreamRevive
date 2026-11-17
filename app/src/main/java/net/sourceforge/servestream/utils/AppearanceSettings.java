package net.sourceforge.servestream.utils;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/** Theme (light/dark/system) and in-app language settings. */
public final class AppearanceSettings {
    public static final String KEY_THEME = "ui_theme";
    public static final String KEY_LANGUAGE = "ui_language";

    private AppearanceSettings() {
    }

    public static void applyTheme(String value) {
        int mode;
        if ("light".equals(value)) {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if ("dark".equals(value)) {
            mode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    public static void applyLanguage(String value) {
        LocaleListCompat locales = (value == null || "system".equals(value))
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(value);
        AppCompatDelegate.setApplicationLocales(locales);
    }

    /** "system", "en" or "ko" for the language list. */
    public static String currentLanguage() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty() || locales.get(0) == null) {
            return "system";
        }
        String lang = locales.get(0).getLanguage();
        return "ko".equals(lang) ? "ko" : "en".equals(lang) ? "en" : "system";
    }
}
