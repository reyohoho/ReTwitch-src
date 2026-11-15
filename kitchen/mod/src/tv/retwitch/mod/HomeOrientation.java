package tv.retwitch.mod;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.util.Log;
import android.view.View;

/**
 * Home in landscape (LandingSettings.getHomeOrientationMode): every {@code Activity.setRequestedOrientation} call in
 * Twitch's code comes here (HomeOrientationPatch). The phone app locks its screens to portrait (31.9.0 {@code dbp.Y}:
 * home, stories, subscriptions...) and frees only the expanded theatre (USER), so back from a landscape stream the home
 * turned portrait again. While the mode is on, a portrait lock of the landing activity outside the expanded theatre
 * becomes USER (the system's auto-rotate) or LOCKED (keeps the current rotation).
 */
public final class HomeOrientation {

    private static final String TAG = "RTOrientation";

    private HomeOrientation() {
    }

    public static void request(Activity activity, int requested) {
        int result = requested;
        try {
            result = map(activity, requested);
            if (Log.isLoggable(TAG, Log.DEBUG) || result != requested) {
                StackTraceElement caller = new Throwable().getStackTrace()[1];
                Log.i(TAG, activity.getClass().getSimpleName() + ": " + requested + " -> " + result + " from "
                        + caller.getClassName() + "." + caller.getMethodName() + ", player mode " + ChannelTracker.playerMode());
            }
        } catch (Throwable t) {
            Log.w(TAG, "map failed", t);
        }
        activity.setRequestedOrientation(result);
    }

    /**
     * Back from the theatre Twitch asks for USER, then UNSPECIFIED, then SENSOR_PORTRAIT within ~120 ms; with auto-rotate
     * off the first two already mean "as the system is turned", i.e. portrait. So "keep" takes all of them, and only an
     * explicit landscape request passes.
     */
    static int map(Activity activity, int requested) {
        int mode = LandingSettings.getHomeOrientationMode();
        if (mode == 0 || !activity.getClass().getName().endsWith(".ViewerLandingActivity") || theatreExpanded(activity)) {
            return requested;
        }
        return mapOutsideTheatre(mode, requested);
    }

    /** What the landing activity asks for outside the expanded theatre, by the mode (1 auto-rotate, 2 keep). */
    static int mapOutsideTheatre(int mode, int requested) {
        if (mode == 1) {
            return isPortraitLock(requested) ? ActivityInfo.SCREEN_ORIENTATION_USER : requested;
        }
        return isLandscape(requested) ? requested : ActivityInfo.SCREEN_ORIENTATION_LOCKED;
    }

    private static boolean isPortraitLock(int requested) {
        return requested == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || requested == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                || requested == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
    }

    private static boolean isLandscape(int requested) {
        return requested == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                || requested == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
    }

    /**
     * The full-size theatre: its own rotate button turns it back to portrait. The mini player is home. By the player
     * mode, not the views: on Back Twitch asks for portrait ~100 ms in, while the theatre still has its full size.
     */
    private static boolean theatreExpanded(Activity activity) {
        View player = activity.findViewById(UiHider.id(activity, "draggable_layout"));
        return player != null && player.isShown() && !ChannelTracker.playerMinimized();
    }
}
