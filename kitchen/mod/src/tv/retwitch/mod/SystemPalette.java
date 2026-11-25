package tv.retwitch.mod;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** The system's wallpaper tones for the ordinary light/dark Twitch interface on Android 12+. */
public final class SystemPalette {
    private static final String TAG = "RTPalette";

    private SystemPalette() { }

    static boolean enabled() {
        return Build.VERSION.SDK_INT >= 31 && Toggles.on(Toggles.SYSTEM_PALETTE);
    }

    /** Android ARGB in the low 32 bits. Used by Compose's light and dark theme constructors. */
    public static long color(long argb) {
        if (!enabled()) return argb;
        int id;
        switch ((int) argb) {
            case 0xff040109: case 0xff0d031c: case 0xff15052e: case 0xff23094e:
                id = android.R.color.system_accent1_900; break;
            case 0xff330c6e: case 0xff451093: id = android.R.color.system_accent1_800; break;
            case 0xff5c16c5: case 0xff772ce8: id = android.R.color.system_accent1_700; break;
            case 0xff9147ff: id = android.R.color.system_accent1_600; break;
            case 0xffa970ff: id = android.R.color.system_accent1_400; break;
            case 0xffbf94ff: case 0xffc0adff: id = android.R.color.system_accent1_300; break;
            case 0xffd1b3ff: id = android.R.color.system_accent1_200; break;
            case 0xffe3d1ff: id = android.R.color.system_accent1_100; break;
            case 0xffede0ff: case 0xfff3ebff: id = android.R.color.system_accent1_50; break;

            case 0xff0e0e10: id = android.R.color.system_neutral1_900; break;
            case 0xff18181b: case 0xff1f1f23: id = android.R.color.system_neutral2_900; break;
            case 0xff26262c: case 0xff323239: id = android.R.color.system_neutral2_800; break;
            case 0xff3b3b44: case 0xff53535f: id = android.R.color.system_neutral2_700; break;
            case 0xff848494: id = android.R.color.system_neutral2_500; break;
            case 0xffadadb8: id = android.R.color.system_neutral2_300; break;
            case 0xffc8c8d0: case 0xffd3d3d9: id = android.R.color.system_neutral2_200; break;
            case 0xffdedee3: case 0xffe6e6ea: id = android.R.color.system_neutral1_100; break;
            case 0xffefeff1: id = android.R.color.system_neutral1_50; break;
            case 0xfff7f7f8: id = android.R.color.system_neutral1_10; break;
            default: return argb;
        }
        try {
            Context context = AppContext.get();
            return context == null ? argb : Integer.toUnsignedLong(context.getColor(id));
        } catch (RuntimeException e) {
            Log.w(TAG, "system colour unavailable", e);
            return argb;
        }
    }

    /** The first token of the verified light/dark theme constructors. Overlay themes have translucent first tokens. */
    public static boolean composeTheme(long first) {
        int argb = (int) (first >>> 32);
        return enabled() && (argb == 0xff772ce8 || argb == 0xffa970ff || argb == 0xfff7f7f8 || argb == 0xff0e0e10);
    }

    public static long composeColor(boolean enabled, long packed) {
        return enabled && (int) packed == 0 ? color(packed >>> 32) << 32 : packed;
    }

    /** The landing window sets light system-bar icons in both modes, including the edge-to-edge path. */
    public static int landingBar(int original) {
        if (!enabled()) return original;
        try {
            Context context = AppContext.get();
            return context == null ? original : context.getColor(android.R.color.system_neutral1_900);
        } catch (RuntimeException e) {
            Log.w(TAG, "landing bar colour unavailable", e);
            return original;
        }
    }

    /** The subscription dialog has its own theme. Change only the bound legal-link text or indeterminate arc. */
    public static void subscriptionControl(View view) {
        if (view == null || !enabled()) return;
        try {
            Context context = view.getContext();
            boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int accent = context.getColor(dark ? android.R.color.system_accent1_300 : android.R.color.system_accent1_700);
            if (view instanceof TextView) ((TextView) view).setLinkTextColor(accent);
            else if (view instanceof ProgressBar) ((ProgressBar) view).setIndeterminateTintList(ColorStateList.valueOf(accent));
        } catch (RuntimeException e) {
            Log.w(TAG, "subscription control colour unavailable", e);
            CrashReporter.report(TAG, "subscription control colour unavailable", e);
        }
    }

    static void install(Context context) {
        if (enabled()) Api31.install(context);
    }

    private static final class Api31 {
        private static ResourcesLoader loader;
        private static final Set<Resources> installed = Collections.newSetFromMap(new WeakHashMap<>());

        static synchronized void install(Context context) {
            Resources resources = context.getResources();
            if (installed.contains(resources)) return;
            try {
                if (loader == null) {
                    // loadFromTable needs a standalone file descriptor; the bundled asset may be deflated.
                    FileDescriptor fd = Os.memfd_create("retwitch-palette", 0);
                    try (InputStream in = context.getAssets().open("retwitch-palette.arsc");
                         FileOutputStream out = new FileOutputStream(fd)) {
                        byte[] block = new byte[4096];
                        for (int n; (n = in.read(block)) != -1; ) out.write(block, 0, n);
                        Os.lseek(fd, 0, OsConstants.SEEK_SET);
                        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fd)) {
                            ResourcesLoader fresh = new ResourcesLoader();
                            fresh.addProvider(ResourcesProvider.loadFromTable(pfd, null));
                            loader = fresh;
                        }
                    } finally {
                        if (fd.valid()) Os.close(fd);
                    }
                }
                resources.addLoaders(loader);
                installed.add(resources);
            } catch (Throwable t) {
                Log.w(TAG, "could not install native colours", t);
                CrashReporter.report(TAG, "could not install native colours", t);
            }
        }
    }
}
