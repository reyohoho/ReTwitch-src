package tv.retwitch.mod;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;
import org.json.JSONObject;

import java.util.Locale;

public final class PortalPalette {
    private static String hex(Context context, int id) {
        return String.format(Locale.ROOT, "#%06x", SystemPalette.tone(context, id) & 0xffffff);
    }

    private static String surfaces(Context c, boolean dark) {
        return "--color-background-body:" + hex(c, dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50)
                + "!important;--color-background-base:" + hex(c, dark ? android.R.color.system_neutral2_900 : android.R.color.system_neutral1_0)
                + "!important;--color-background-alt:" + hex(c, dark ? android.R.color.system_neutral2_800 : android.R.color.system_neutral1_50)
                + "!important;--color-text-base:" + hex(c, dark ? android.R.color.system_neutral1_50 : android.R.color.system_neutral1_900)
                + "!important;--color-text-alt:" + hex(c, dark ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700)
                + "!important;";
    }

    private static boolean route(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }

    /** Called by the Twitch portal and React Native web views when their main page finishes loading. */
    public static void apply(WebView view, String url) {
        if (view == null || url == null || !SystemPalette.enabled()) return;
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equals(uri.getScheme()) || !"m.twitch.tv".equals(uri.getEncodedAuthority())) return;
            String path = uri.getEncodedPath();
            if (path == null) return;
            boolean clips = route(path, "/wv/clips/creation") || route(path, "/wv/clip-creation");
            if (!clips && !route(path, "/wv/moderator/settings") && !route(path, "/wv/moderator/summary")) return;
            Context c = view.getContext();
            String fill = hex(c, android.R.color.system_accent1_600);
            String light = hex(c, android.R.color.system_accent1_200);
            String dark = hex(c, android.R.color.system_accent1_700);
            String css = ":root,.tw-root--theme-light,.tw-root--theme-dark{--rt-fill:" + fill + ";--rt-link:" + dark + ";" + surfaces(c, false) + "}"
                    + ".tw-root--theme-dark{--rt-link:" + light + ";" + surfaces(c, true) + "}"
                    + ":root,.tw-root--theme-light,.tw-root--theme-dark{accent-color:var(--rt-fill);"
                    + "--color-background-button-brand:var(--rt-fill)!important;--color-background-button-brand-hover:" + dark + "!important;"
                    + "--color-background-button-brand-active:" + dark + "!important;--color-background-button-primary-default:var(--rt-fill)!important;"
                    + "--color-background-accent:var(--rt-fill)!important;--color-fill-brand:var(--rt-link)!important;"
                    + "--color-text-link:var(--rt-link)!important;--color-text-brand:var(--rt-link)!important;"
                    + "--color-border-brand:var(--rt-link)!important;--color-border-toggle-checked:var(--rt-link)!important;"
                    + "--color-background-toggle-handle-checked:var(--rt-link)!important}";
            if (clips) css += ".draggable-slider-handle--purple{background-color:var(--rt-fill)!important}"
                    + ".draggable-slider__overlay--purple{border-color:var(--rt-fill)!important}"
                    + ".clip-editor-timeline-background,.draggable-slider__overlay--purple{background-color:" + light + "a6!important}"
                    + ".tw-root--theme-dark .clip-editor-timeline-background,.tw-root--theme-dark .draggable-slider__overlay--purple{background-color:" + dark + "a6!important}"
                    + ".clip-editor-thumb:hover,.clip-editor-thumb:active{border-color:var(--rt-link)!important}";
            // Navigation can finish between the callback and evaluation. Only style that same first-party page.
            view.evaluateJavascript("(function(){if(location.origin!=='https://m.twitch.tv'||location.pathname!=="
                    + JSONObject.quote(path) + "||document.getElementById('retwitch-palette'))return;"
                    + "var s=document.createElement('style');s.id='retwitch-palette';s.textContent=" + JSONObject.quote(css)
                    + ";document.head.appendChild(s)})()", null);
        } catch (Throwable e) {
            CrashReporter.report("RTPalette", "portal theme failed", e);
        }
    }
}
