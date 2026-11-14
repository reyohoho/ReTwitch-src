package tv.retwitch.mod;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * PIP_CLEAN_VIDEO: only the video in picture-in-picture. Twitch keeps its callouts (watch streaks and other personal
 * ones), celebrations, one-tap buttons, highlights and the overlay chat over the player, and in the small window they
 * cover much of it. They go transparent while the activity is in PiP: alpha, not visibility, so Twitch's own showing
 * and hiding of them goes on as before, and nothing it dismissed meanwhile comes back after PiP.
 */
final class PipOverlays {

    /** Over the player: live theatre (theatre_coordinator, _land) and the VOD player (player_view). */
    static final String[] IDS = {
            "private_callouts_container", "private_callouts_parent_container", "celebrations_overlay_container",
            "one_tap_button_overlay", "one_tap_button_tooltip_overlay", "one_tap_landscape_store_container",
            "one_tap_pills_container", "one_tap_skip_breakpoint_animation_container",
            "theatre_community_highlight_container", "one_chat_overlay_container",
    };
    /** On a faded view: its alpha before PiP. */
    private static final int ALPHA_TAG_KEY = 0x7e700d75;
    /** Views faded for the current PiP; main thread only. */
    private static final List<WeakReference<View>> FADED = new ArrayList<>();

    private PipOverlays() {
    }

    /** From UiHider's pass in the theatre's activity. */
    static void apply(Activity activity, View decor) {
        if (!activity.isInPictureInPictureMode() || !Toggles.on(Toggles.PIP_CLEAN_VIDEO)) {
            restore();
            return;
        }
        for (String id : IDS) {
            for (View view : UiHider.findAll(activity, decor, id)) {
                fade(view);
            }
        }
    }

    /**
     * Every frame while something is faded: a callout that fades itself in (an alpha animation) would show again
     * between passes, since only its alpha changes.
     */
    static void keep() {
        for (int i = 0; i < FADED.size(); i++) {
            View view = FADED.get(i).get();
            if (view != null && view.getAlpha() != 0f) {
                view.setAlpha(0f);
            }
        }
    }

    /** Whether {@code view} is in an activity that's in picture-in-picture with the toggle on (our own overlays). */
    static boolean hides(View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity && ((Activity) context).isInPictureInPictureMode()
                && Toggles.on(Toggles.PIP_CLEAN_VIDEO);
    }

    private static void fade(View view) {
        if (view.getTag(ALPHA_TAG_KEY) == null) {
            view.setTag(ALPHA_TAG_KEY, view.getAlpha());
            FADED.add(new WeakReference<>(view));
        }
        if (view.getAlpha() != 0f) {
            view.setAlpha(0f);
        }
    }

    // shortcut: an alpha saved halfway through Twitch's own fade comes back as it was; fine unless one shows up dim.
    private static void restore() {
        for (WeakReference<View> ref : FADED) {
            View view = ref.get();
            Object saved = view != null ? view.getTag(ALPHA_TAG_KEY) : null;
            if (saved instanceof Float) {
                view.setTag(ALPHA_TAG_KEY, null);
                view.setAlpha((Float) saved);
            }
        }
        FADED.clear();
    }
}
