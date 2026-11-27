package tv.retwitch.mod;

import android.app.Activity;
import android.util.Log;
import tv.retwitch.mod.ads.VaftLog;

/** The native clip page can unbind its view before its playback presenter is destroyed. */
public final class ClipExit {
    private static final String TAG = "RTClipExit";

    private ClipExit() { }

    public static void onDestroyView(Object fragment, Object playback) {
        try {
            if (playback == null || !Toggles.on(Toggles.STOP_CLOSED_CLIP)) return;
            Activity activity = (Activity) fragment.getClass().getMethod("getActivity").invoke(fragment);
            if (shouldPause(fragment, activity != null && activity.isFinishing(),
                    activity != null && activity.isChangingConfigurations())) {
                playback.getClass().getMethod("pause").invoke(playback);
                VaftLog.log("closed clip: paused its playback presenter");
            }
        } catch (Throwable t) {
            Log.w(TAG, "close clip playback", t);
            CrashReporter.report(TAG, "close clip playback", t);
        }
    }

    static boolean shouldPause(Object fragment, boolean finishing, boolean recreating) throws ReflectiveOperationException {
        if (recreating) return false;
        if (finishing) return true;
        // The pager is usually the fragment Android removes; its individual clip pages are children.
        for (Object page = fragment; page != null; page = page.getClass().getMethod("getParentFragment").invoke(page)) {
            if (Boolean.TRUE.equals(page.getClass().getMethod("isRemoving").invoke(page))) return true;
        }
        return false;
    }
}
