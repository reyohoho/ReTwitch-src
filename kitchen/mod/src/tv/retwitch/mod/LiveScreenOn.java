package tv.retwitch.mod;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;

/**
 * Twitch's layouts keep the screen on for as long as the theatre is open, paused or not: {@code keepScreenOn="true"}
 * on the theatre root ({@code draggable_container}), on {@code player_view}'s container and on the feed pager under the
 * theatre (PR #50). These let go while the theatre's player is paused or over ({@link Toggles#SCREEN_OFF_PAUSED}), and
 * with {@link Toggles#SCREEN_OFF_STALLED} once nothing has played for 30 s; the system screen timeout does the rest.
 * The state is the audible player's ({@link PlayerHook#onStateChanged}), so a muted stream keeps Twitch's hold.
 */
final class LiveScreenOn {
    private static final String TAG = "RTScreenOn";
    /** Every view with {@code keepScreenOn="true"} in the viewer's layouts (31.9.0); all are in {@link UiHider}'s scan. */
    private static final String[] HOLDERS = {"draggable_container", "player_and_chat_vertical_container",
            "discovery_feed_pager_container"};
    private static final int RELEASED_KEY = 0x7e700d85;
    private static final long STALL_MS = 30_000L;
    /** A reload or a quality switch passes IDLE / READY: decide once the state has held a moment. */
    private static final long SETTLE_MS = 1_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable APPLY = () -> {
        Activity activity = LiveScreenOn.activity.get();
        if (activity != null) {
            apply(activity, activity.getWindow().getDecorView());
        }
    };

    /** Main thread only. */
    private static String state;
    private static long lastPlayed;
    private static boolean released;
    private static WeakReference<Activity> activity = new WeakReference<>(null);

    private LiveScreenOn() {
    }

    static void onTheatreOpened() {
        state = null;
        lastPlayed = SystemClock.elapsedRealtime();
    }

    /** Each state of the theatre's (audible) player, main thread. */
    static void onState(String name) {
        state = name;
        long now = SystemClock.elapsedRealtime();
        if ("PLAYING".equals(name)) {
            lastPlayed = now;
        }
        MAIN.removeCallbacks(APPLY);
        MAIN.postDelayed(APPLY, SETTLE_MS);
        if (loading() && Toggles.on(Toggles.SCREEN_OFF_STALLED)) {
            MAIN.postDelayed(APPLY, Math.max(SETTLE_MS, lastPlayed + STALL_MS - now));
        }
    }

    private static boolean loading() {
        return "BUFFERING".equals(state) || "READY".equals(state);
    }

    private static boolean release() {
        if (state == null || "PLAYING".equals(state) || !PlayerHook.theatreOpen()) {
            return false;
        }
        if (loading()) {
            return Toggles.on(Toggles.SCREEN_OFF_STALLED) && SystemClock.elapsedRealtime() - lastPlayed >= STALL_MS;
        }
        return Toggles.on(Toggles.SCREEN_OFF_PAUSED); // IDLE (paused), ENDED
    }

    /** From the UiHider pass of ViewerLandingActivity, and a moment after each player state. */
    static void apply(Activity activity, View decor) {
        LiveScreenOn.activity = new WeakReference<>(activity);
        boolean release = release();
        if (release != released) {
            released = release;
            Log.i(TAG, (release ? "released, " : "held, ") + state);
        }
        for (String id : HOLDERS) {
            for (View view : UiHider.findAll(activity, decor, id)) {
                if (release && view.getKeepScreenOn()) {
                    view.setKeepScreenOn(false);
                    view.setTag(RELEASED_KEY, Boolean.TRUE);
                } else if (!release && view.getTag(RELEASED_KEY) != null) {
                    view.setTag(RELEASED_KEY, null);
                    view.setKeepScreenOn(true);
                }
            }
        }
    }
}
