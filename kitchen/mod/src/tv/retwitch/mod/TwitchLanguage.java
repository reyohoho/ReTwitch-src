package tv.retwitch.mod;

import android.app.Application;
import android.app.LocaleManager;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** Twitch's interface language, separate from the ReTwitch translation catalog (#79). */
public final class TwitchLanguage {
    // Languages with Twitch's own translated UI in the merged 31.x bundle.
    private static final String[] TAGS = {"", "en", "ar", "bg", "cs", "da", "de", "el", "es-ES", "es-MX", "es-US",
            "fi", "fr", "hu", "it", "ja", "ko", "nb", "nl", "pl", "pt", "pt-BR", "ro", "ru", "sk", "sv", "th",
            "tr", "uk", "vi", "zh", "zh-HK", "zh-TW"};
    private static final String KEY = "twitch_language";
    private static volatile Locale systemLocale;

    private TwitchLanguage() { }

    private static String legacyTag(Context c) {
        return c.getSharedPreferences(TranslationFiles.PREFS, Context.MODE_PRIVATE).getString(KEY, "");
    }

    static void setting(SettingsScreen s) {
        Context c = s.context;
        LocaleList selected = Build.VERSION.SDK_INT >= 33 ? c.getSystemService(LocaleManager.class).getApplicationLocales()
                : LocaleList.forLanguageTags(legacyTag(c));
        String current = selected.isEmpty() ? "" : selected.get(0).toLanguageTag();
        String[] labels = new String[TAGS.length];
        int index = 0;
        for (int i = 0; i < TAGS.length; i++) {
            Locale locale = Locale.forLanguageTag(TAGS[i]);
            labels[i] = i == 0 ? TranslationFiles.name("") : locale.getDisplayName(locale);
            if (TAGS[i].equalsIgnoreCase(current)) index = i;
        }
        s.markNew("0.5.42-beta").compare(Strings.pick("Язык телефона", "Phone language"),
                        Strings.pick("Выбранный язык Twitch", "Selected Twitch language"))
                .choice(Strings.pick("Язык Twitch", "Twitch language"),
                        Strings.pick("Лента, плеер и другие экраны Twitch", "Feed, player and other Twitch screens"),
                        labels, index, Build.VERSION.SDK_INT < 33, i -> {
                            if (Build.VERSION.SDK_INT >= 33) {
                                c.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(TAGS[i]));
                            } else {
                                c.getSharedPreferences(TranslationFiles.PREFS, Context.MODE_PRIVATE).edit()
                                        .putString(KEY, TAGS[i]).apply();
                            }
                        });
    }

    static Locale systemLocale() {
        Locale locale = systemLocale;
        return locale != null ? locale : Locale.getDefault();
    }

    private static void readSystemLocale(Context c) {
        LocaleList locales = Build.VERSION.SDK_INT >= 33 ? c.getSystemService(LocaleManager.class).getSystemLocales()
                : Resources.getSystem().getConfiguration().getLocales();
        if (!locales.isEmpty()) systemLocale = locales.get(0);
    }

    /** Called before Twitch initializes its RN host; the platform handles per-app locales since API 33. */
    public static void install(Application app) {
        try {
            readSystemLocale(app);
            String tag = legacyTag(app);
            LocaleList locales = LocaleList.forLanguageTags(tag);
            if (Build.VERSION.SDK_INT >= 33 && !tag.isEmpty()) {
                // Transfer a pre-33 choice once, preserving an override already set in Android Settings.
                LocaleManager manager = app.getSystemService(LocaleManager.class);
                if (manager.getApplicationLocales().isEmpty()) manager.setApplicationLocales(locales);
                app.getSharedPreferences(TranslationFiles.PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
            }
            boolean legacyOverride = Build.VERSION.SDK_INT < 33 && !locales.isEmpty();
            if (legacyOverride) applyResources(app.getResources(), locales);
            app.registerComponentCallbacks(new ComponentCallbacks() {
                public void onConfigurationChanged(Configuration config) {
                    try {
                        readSystemLocale(app);
                        if (legacyOverride) applyResources(app.getResources(), locales);
                    } catch (Throwable t) {
                        CrashReporter.report("RTLanguage", "configuration change failed", t);
                    }
                }
                public void onLowMemory() { }
            });
        } catch (Throwable t) {
            CrashReporter.report("RTLanguage", "application locale failed", t);
        }
    }

    private static void applyResources(Resources resources, LocaleList locales) {
        LocaleList.setDefault(locales);
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocales(locales);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    /** Called during AppCompatActivity.attachBaseContext on Android 8-12, before an activity inflates its UI. */
    public static Context wrap(Context base) {
        try {
            if (Build.VERSION.SDK_INT < 33) {
                String tag = legacyTag(base);
                if (!tag.isEmpty()) {
                    Configuration override = new Configuration();
                    override.setLocales(LocaleList.forLanguageTags(tag));
                    return base.createConfigurationContext(override);
                }
            }
        } catch (Throwable t) {
            CrashReporter.report("RTLanguage", "activity locale failed", t);
        }
        return base;
    }
}
