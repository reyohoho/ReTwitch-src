package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.os.Build;
import android.view.WindowInsets;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hides optional UI by view-id *name* (resource names survive R8): player
 * overlay buttons (searched only inside the player overlay, so "share" etc.
 * elsewhere in the app stay), Follow/Subscribe, the chat Bits and settings
 * buttons, extensions, leaderboards, the mature content label (and answers its
 * warning), bottom navigation items and -- in landscape -- the side chat's
 * header and input and the One Chat input.
 * Runs on every global layout of Twitch's activities and checks again before
 * that layout is drawn, after Twitch's transition callbacks. Only views that
 * are VISIBLE are touched; the landscape-only ones are put back when the condition ends.
 */
public final class UiHider {

    private static final String TAG = "RTUiHider";
    /** Root of both player overlay layouts (theatre and the newer widget). */
    private static final String PLAYER_OVERLAY = "player_overlay";
    /** Per toggle: the button's id in either overlay layout. */
    private static final String[][] PLAYER_IDS = {
            {"cast_button", "media_route_button"}, {"share_button"},
            {"create_clip_text_button", "create_clip_button_compose_view"},
    };
    /** Full screen and chat mode in both bottom bars (portrait widget, theatre); these ids exist only there. */
    private static final String[] PLAYER_BAR_IDS = {"fullscreen_button", "chat_mode_button"};
    /** Bottom navigation (phones) and navigation rail (tablets) share one menu. */
    private static final String[] NAV_VIEWS = {"viewer_landing_bottom_navigation", "viewer_landing_navigation_rail"};
    /** The side chat column of the live theatre and of VODs. */
    private static final String[] SIDE_CHAT = {"chat_wrapper", "landscape_chat_container"};
    private static final int HIDDEN_TAG_KEY = 0x7e700d40;
    private static final int AUDIO_UNMUTED_TAG_KEY = 0x7e700d72;
    private static final int REMEASURED_TAG_KEY = 0x7e700d46;
    private static final int EMOTE_TOUCH_TAG_KEY = 0x7e700d47;
    private static final int PRESSED_TAG_KEY = 0x7e700d41;
    private static final int POINTS_PARAMS_TAG_KEY = 0x7e700d50;
    /** On the chat input: the keyboard has been up since it got focus / we hid its cursor. */
    private static final int IME_SEEN_TAG_KEY = 0x7e700d48;
    private static final int CURSOR_TAG_KEY = 0x7e700d49;
    /** Follow/Subscribe in the player: collapsed metadata and One Chat, extended metadata, overlays. */
    private static final String[] FOLLOW_SUB = {
            "follow_subscribe_button", "extended_follow_button_container", "extended_subscribe_button_container",
            // Compose strip of the extended metadata: its notifications bell can't be split off, so it goes too.
            // The native bell as well: Twitch shows the native row until the strip's state arrives (issue #5).
            "metadata_buttons_container", "extended_notification_button_container",
            "theatre_overlay_subscribe_button_container", "subscribe_button_container",
    };
    /** Extension entry points: the icons (wherever inflated) and their containers above chat. */
    private static final String[] EXTENSIONS = {
            "extension_icons_container", "extension_button_container", "leaderboards_extensions_container",
    };
    /** Sponsored channel skins (creator sponsorships): theatre backgrounds, overlay, brand avatars; "Includes Paid Promotion". */
    private static final String[] CHANNEL_SKINS = {
            "channelskins_container", "channelskins_top_background_container", "channelskins_bottom_background_container",
            "channelskins_overlay_container", "channel_skins_creator_brand_view",
            "sponsored_streams_container_landscape", "sponsored_streams_container_portrait",
    };
    /** One Chat buttons that start typing or sending, next to its "Send a message" pill. */
    private static final String[] ONE_CHAT_INPUT = {"keyboard_button", "emote_picker", "bits_button"};
    /** Beside the landscape chat input: hidden while typing so the field gets the room. */
    private static final String[] TYPING_HIDDEN = {
            "community_points_button_container", "right_side_community_points_button_container", "view_chat_settings",
    };
    /** Opened in the chat column: poll (poll_vote_layout), two- and multi-outcome prediction. */
    private static final String[] CHAT_PANELS = {"poll_items_container", "prediction_event_container", "outcomes_container",
            "prediction_prompt", "option_details_pager"};

    /** Single ids looked up under the decor in a pass (with the arrays above); one walk finds them all. */
    private static final String[] SCANNED = {
            PLAYER_OVERLAY, "unfollow_button", "follow_button", "mature_disclosure",
            "stop_sign_button_affirmative", "stop_sign_button_affirmative_horizontal",
            "one_chat_message_input_view_container", "split_button", "bit_picker", "bits_button",
            "leaderboards_container", "leaderboards_icon", "paid_pinned_chat_container",
            "custom_create_bottom_nav_button", "chat_mode_snackbar", "view_chat_settings",
            "chat_banned_replacement_container", "community_points_button_layout", "draggable_container",
            // PlayerHook.attachLatencyLabels, called from the pass.
            "play_pause_container", "play_pause_fast_seek_view", "message_input",
            "fullscreen_button", "chat_mode_button", "emote_picker", "chat_header_container", "emoticon_picker",
            "chomments_header_container", "mod_dashboard_button", "chat_restrictions_container", "minimized_overlay",
            "seek_forward_button", "seek_rewind_button",
            "community_points_button_container", "right_side_community_points_button_container",
            // An id missing here costs a whole extra tree walk per pass (findAll falls back to collect).
            "toggle_player_mode", "subscribe_button", "viewer_landing_bottom_navigation_group",
            "play_pause_button", // StreamSwitch.sync
            "chat_more_messages_below_text", // NewMessages.attach
    };

    private static final Map<String, Integer> IDS = new HashMap<>();
    /** Decor of the running pass, its views by id (pre-order, nested same-id dropped) and the RN Turbo views. */
    private static View scanRoot;
    private static SparseArray<List<View>> scan;
    private static List<View> scanTurbo;
    /** Pass nesting: hiding a focused view moves focus, which runs a pass from inside this one. */
    private static int passDepth;
    /** Views hidden by hide/hideWhile so far: the layout pass compares it to know it hid something. */
    private static int hiddenCount;
    /** Views hidden by hide/hideWhile, for the per-frame check that Twitch hasn't shown one again (main thread only). */
    private static final Set<View> HIDDEN = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Map<Class<?>, Method> MENU_GETTERS = new HashMap<>();
    private static boolean installed;
    private static boolean sideChatTouched;
    private static boolean chatModeTouched;
    private static boolean typingTouched;
    private static boolean emotePortraitTouched;
    private static boolean unfollowTouched;
    private static boolean pointsTouched;
    private static boolean replayHeaderTouched;
    private static boolean streamManagerTouched;
    private static boolean restrictionsTouched;
    private static boolean overlayCalloutMarked;
    /** On draggable_layout: when we last pressed the mini player's ✕ (one press per close). */
    private static final int MINI_CLOSE_TAG_KEY = 0x7e700d73;
    private static String unfollowLabel;

    private UiHider() {
    }

    /** Called once from the injected {@code TwitchApplication.onCreate}. */
    public static void install(Application application) {
        try {
            if (installed || application == null) {
                return;
            }
            installed = true;
            UiStallLog.startIfEnabled(application);
            BanDiag.startIfEnabled(application);
            tv.retwitch.mod.emotes.RteFriends.install(application);
            Danmaku.install(application);
            LoggedOut.install(application);
            ModUpdate.install(application);
            DebugServer.install(application);
            QuickSetup.install(application);
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, Bundle b) { attach(a); }
                public void onActivityStarted(Activity a) { }
                public void onActivityResumed(Activity a) { }
                public void onActivityPaused(Activity a) { }
                public void onActivityStopped(Activity a) { }
                public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            CrashReporter.report(TAG, "install failed", t);
        }
    }

    private static void attach(final Activity activity) {
        if (activity.getWindow() == null || !activity.getClass().getName().startsWith("tv.twitch")) {
            return;
        }
        final View decor = activity.getWindow().getDecorView();
        final ViewTreeObserver.OnPreDrawListener checkBeforeDraw = new ViewTreeObserver.OnPreDrawListener() {
            private boolean skipped;

            @Override
            public boolean onPreDraw() {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                // Every view a pass hid or found is in HIDDEN, so a transition can only bring back one of those.
                if (!reshown(decor)) {
                    skipped = false;
                    return true;
                }
                boolean before = decor.isLayoutRequested();
                long t0 = UiStallLog.start();
                safeApply(activity, decor);
                UiStallLog.end("UiHider pre-draw", t0);
                // Something hidden now: skip this frame so the next one is laid out without it. Never twice in a row,
                // in case Twitch shows it again before every draw.
                skipped = !skipped && !before && decor.isLayoutRequested();
                return !skipped;
            }
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                long t0 = UiStallLog.start();
                int hiddenBefore = hiddenCount;
                safeApply(activity, decor);
                if (hiddenCount != hiddenBefore) {
                    // A delayed transition (the player metadata's) takes its end bounds in pre-draw and suppresses
                    // layout until it ends: lay out now, or the room of the view just hidden stays empty (issue #5).
                    try {
                        decor.measure(View.MeasureSpec.makeMeasureSpec(decor.getWidth(), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(decor.getHeight(), View.MeasureSpec.EXACTLY));
                        decor.layout(decor.getLeft(), decor.getTop(), decor.getRight(), decor.getBottom());
                    } catch (Throwable t) {
                        Log.w(TAG, "relayout failed", t);
                        CrashReporter.report(TAG, "relayout failed", t);
                    }
                }
                UiStallLog.end("UiHider", t0);
                // Transitions can restore visibility in pre-draw. Run after their already-registered callbacks.
                ViewTreeObserver observer = decor.getViewTreeObserver();
                observer.removeOnPreDrawListener(checkBeforeDraw);
                observer.addOnPreDrawListener(checkBeforeDraw);
            }
        });
        // A view we hid, shown again while a transition suppresses layout: its requestLayout stops at the suppressed
        // parent, so no layout pass and no pass of ours, only draws (issue #19, the chat input for ~0.8 s). Every
        // frame only checks the views we hid; a whole pass runs when one of them is visible again.
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (reshown(decor)) {
                    long t0 = UiStallLog.start();
                    safeApply(activity, decor);
                    UiStallLog.end("UiHider reshown", t0);
                }
                return true;
            }
        });
        // Focus moving into / out of the chat input doesn't always relayout.
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                safeApply(activity, decor);
            }
        });
    }

    /**
     * While the theatre's chat loads, Twitch puts live_chat_placeholder in chat_view_container: a frame whose only
     * child is a grey rounded bar in the input's place (issue #18). Twitch removes it once the chat is there.
     */
    private static void hideInputPlaceholder(View chat) {
        if (!(chat instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) chat;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() == 1) {
                View bar = ((ViewGroup) child).getChildAt(0);
                if (bar.getClass().getSimpleName().equals("PlaceholderRoundedRectangle")) {
                    hide(bar);
                }
            }
        }
    }

    /** Whether a view of this window that we hid is visible again; such views leave the set (the pass re-adds them). */
    private static boolean reshown(View decor) {
        boolean found = false;
        IBinder window = decor.getWindowToken();
        for (java.util.Iterator<View> it = HIDDEN.iterator(); it.hasNext(); ) {
            View view = it.next();
            if (view.getVisibility() == View.VISIBLE && view.getWindowToken() == window && window != null) {
                it.remove();
                found = true;
            }
        }
        return found;
    }

    private static void safeApply(Activity activity, View decor) {
        try {
            apply(activity, decor);
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
            CrashReporter.report(TAG, "apply failed", t);
        }
    }

    private static void apply(Activity activity, View decor) {
        if (passDepth++ == 0) {
            scanRoot = decor;
            scan = null;
        }
        try {
            applyPass(activity, decor);
        } finally {
            if (--passDepth == 0) {
                scanRoot = null;
                scan = null;
                scanTurbo = null;
            }
        }
    }

    private static void applyPass(Activity activity, View decor) {
        boolean[] player = {
                Toggles.on(Toggles.HIDE_PLAYER_CAST), Toggles.on(Toggles.HIDE_PLAYER_SHARE),
                Toggles.on(Toggles.HIDE_PLAYER_CLIP),
        };
        if (player[0] || player[1] || player[2]) {
            for (View overlay : findAll(activity, decor, PLAYER_OVERLAY)) {
                for (int i = 0; i < PLAYER_IDS.length; i++) {
                    if (player[i]) {
                        for (String id : PLAYER_IDS[i]) {
                            hide(find(activity, overlay, id));
                        }
                    }
                }
            }
        }
        if (Toggles.on(Toggles.HIDE_PLAYER_FULLSCREEN)) {
            hideAll(activity, decor, PLAYER_BAR_IDS);
        }
        boolean minimize = Toggles.on(Toggles.HIDE_PLAYER_MINIMIZE);
        if (minimize || minimizeTouched) {
            minimizeTouched = minimize;
            // back_button is a common id (the points panel has one too): only the player overlay's.
            for (View overlay : findAll(activity, decor, PLAYER_OVERLAY)) {
                hideWhile(minimize, find(activity, overlay, "back_button"));
            }
        }
        if (Toggles.on(Toggles.AUDIO_ONLY_UNMUTE)) {
            for (View toggle : findAll(activity, decor, "toggle_player_mode")) {
                unmuteAudioOnly(activity, toggle);
            }
        }
        boolean rotate = Toggles.on(Toggles.HIDE_PLAYER_ROTATE);
        boolean mute = Toggles.on(Toggles.HIDE_PLAYER_MUTE);
        if (rotate || mute || squashTouched) {
            squashTouched = rotate || mute;
            for (View overlay : findAll(activity, decor, PLAYER_OVERLAY)) {
                for (View button : findAll(activity, overlay, "rotate_button")) {
                    squashWhile(rotate, button);
                }
                for (View button : findAll(activity, overlay, "mute_button")) {
                    squashWhile(mute, button);
                }
            }
        }
        if (Toggles.on(Toggles.HIDE_FOLLOW_SUB)) {
            hideAll(activity, decor, FOLLOW_SUB);
            // The native row's divider; button_divider is a One Chat id too, so only the bell's sibling.
            for (View bell : findAll(activity, decor, "extended_notification_button_container")) {
                if (bell.getParent() instanceof View) {
                    hide(find(activity, (View) bell.getParent(), "button_divider"));
                }
            }
        }
        applyUnfollow(activity, decor);
        boolean streamManager = Toggles.on(Toggles.HIDE_PROFILE_STREAM_MANAGER);
        if (streamManager || streamManagerTouched) {
            streamManagerTouched = streamManager;
            hideWhileAll(activity, decor, streamManager, "mod_dashboard_button");
        }
        boolean restrictions = Toggles.on(Toggles.HIDE_CHAT_RESTRICTIONS);
        if (restrictions || restrictionsTouched) {
            restrictionsTouched = restrictions;
            hideWhileAll(activity, decor, restrictions, "chat_restrictions_container");
        }
        if (Toggles.on(Toggles.NO_MINI_PLAYER)) {
            for (View overlay : findAll(activity, decor, "minimized_overlay")) {
                if (overlay.isShown() && overlay.getParent() instanceof View) {
                    closeMiniPlayer((View) overlay.getParent());
                }
            }
        }
        if (Toggles.on(Toggles.HIDE_MATURE_WARNING)) {
            hideAll(activity, decor, "mature_disclosure");
            answerStopSign(activity, decor);
        }
        if (Toggles.on(Toggles.HIDE_EXTENSIONS)) {
            hideAll(activity, decor, EXTENSIONS);
        }
        if (Toggles.on(Toggles.BLOCK_DISPLAY_ADS)) {
            hideAll(activity, decor, CHANNEL_SKINS);
        }
        if (Toggles.on(Toggles.BAN_BYPASS)) {
            // "You are banned from Chat" covers the message list; IRC keeps delivering the messages.
            hideAll(activity, decor, "chat_banned_replacement_container");
        }
        if (Toggles.on(Toggles.HIDE_ONE_CHAT_INPUT)) {
            hideOneChatInput(activity, decor);
        }
        if (Toggles.on(Toggles.HIDE_BITS_BUTTON)) {
            // bit_picker: the theatre chat input (party-popper icon); bits_button: the "one chat" overlay.
            hideAll(activity, decor, "bit_picker");
            hideAll(activity, decor, "bits_button");
        }
        if (Toggles.on(Toggles.HIDE_LEADERBOARDS)) {
            hideAll(activity, decor, "leaderboards_container");
            hideAll(activity, decor, "leaderboards_icon");
        }
        if (Toggles.on(Toggles.HIDE_PINNED_CHEERS)) {
            // Pinned cheers: their carousel frame (background_base) keeps 6dp margins when empty -- a grey strip.
            hideAll(activity, decor, "paid_pinned_chat_container");
        }
        if (PromoHook.blocked()) {
            hideRnTurbo(activity, decor);
        }
        if (Toggles.on(Toggles.HIDE_SOCIAL_GIFT_SUBS)) {
            hideAll(activity, decor, "social_gift_subs_banner_compose");
        }
        boolean browse = Toggles.on(Toggles.HIDE_TAB_BROWSE);
        boolean activityTab = Toggles.on(Toggles.HIDE_TAB_ACTIVITY);
        if (browse) {
            hideNavItem(activity, decor, "viewer_bottom_nav_explore");
        }
        if (activityTab) {
            hideNavItem(activity, decor, "viewer_bottom_nav_activity");
        }
        if (Toggles.on(Toggles.HIDE_TAB_CREATE)) {
            hideNavItem(activity, decor, "viewer_bottom_nav_create_placeholder");
            hide(find(activity, decor, "custom_create_bottom_nav_button"));
        }
        centreCreateButton(activity, decor, browse || activityTab);
        for (View button : findAll(activity, decor, "seek_forward_button")) {
            VodSeek.showStep(button, true);
        }
        for (View button : findAll(activity, decor, "seek_rewind_button")) {
            VodSeek.showStep(button, false);
        }
        StreamSwitch.sync(activity, decor);
        applyLandscapeChat(activity, decor);
        if (Toggles.on(Toggles.HIDE_CHAT_SETTINGS_BUTTON)) {
            // After the typing pass, which shows this button again when typing ends.
            hideAll(activity, decor, "view_chat_settings");
        }
        applyIdleCursor(activity, decor);
        PlayerHook.attachLatencyLabels(activity, decor);
        BatteryLabel.attach(activity, decor);
        ChattersCount.attach(activity, decor);
        NewMessages.attach(activity, decor);
    }

    /** Something visible inside: a VISIBLE non-Space view, or a VISIBLE group with such a child (an empty frame isn't). */
    private static boolean hasContent(View view) {
        if (view.getVisibility() != View.VISIBLE || view instanceof android.widget.Space) {
            return false;
        }
        if (!(view instanceof ViewGroup)) {
            return true;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (hasContent(group.getChildAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean squashTouched;
    private static boolean minimizeTouched;

    /** From the chat adapter's bind (AutoModChat.onRowBound): the ⋮ of a video's chat line, which Twitch never hides. */
    static void bindChatRow(View row) {
        int id = id(row.getContext(), "overflow_image_view");
        View menu = id == 0 ? null : row.findViewById(id);
        if (menu != null) {
            hideWhile(Toggles.on(Toggles.HIDE_VOD_ROW_MENU), menu);
        }
    }

    /**
     * Twitch's Audio Only mode shows its switch where the mute button was (issue #10): a muted player stayed silent with
     * nothing to unmute it. Once per switch-on, a muted player is unmuted by the mute button itself: its click (31.9.0
     * f93 case 5) doesn't look at visibility. Muted is the "Mute" description (t4u.A(isMuted=true)).
     */
    private static void unmuteAudioOnly(Activity activity, View toggle) {
        boolean audioOnly = toggle.getVisibility() == View.VISIBLE && toggle instanceof android.widget.CompoundButton
                && ((android.widget.CompoundButton) toggle).isChecked();
        if (!audioOnly) {
            toggle.setTag(AUDIO_UNMUTED_TAG_KEY, null);
            return;
        }
        if (toggle.getTag(AUDIO_UNMUTED_TAG_KEY) != null || !(toggle.getParent() instanceof View)) {
            return;
        }
        View mute = ((View) toggle.getParent()).findViewById(id(activity, "mute_button"));
        int muted = res(activity, "mute_button_text", "string");
        if (mute == null || muted == 0 || !activity.getString(muted).equals(String.valueOf(mute.getContentDescription()))) {
            return;
        }
        // Before the click: if Twitch ignores it, the description stays and the next pass would click again.
        toggle.setTag(AUDIO_UNMUTED_TAG_KEY, Boolean.TRUE);
        mute.performClick();
    }

    /**
     * Hides a player button but keeps its visibility (HIDE_PLAYER_ROTATE, HIDE_PLAYER_MUTE): ReloadHook's strip anchors
     * to the shown one of these and fades with it, and in portrait the rotate button is the only one shown. 1 px wide,
     * not 0 (match constraints in a ConstraintLayout), and scaled to nothing. A click from the mod (full screen swipe,
     * stream arrows) still works.
     */
    private static void squashWhile(boolean on, View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        Object saved = view.getTag(SQUASH_TAG_KEY);
        if (lp == null || on == (saved != null)) {
            return;
        }
        if (on) {
            view.setTag(SQUASH_TAG_KEY, lp.width);
            lp.width = 1;
            view.setScaleX(0f);
            view.setScaleY(0f);
        } else {
            view.setTag(SQUASH_TAG_KEY, null);
            lp.width = saved instanceof Integer ? (Integer) saved : ViewGroup.LayoutParams.WRAP_CONTENT;
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
        view.setLayoutParams(lp);
    }

    /** A bottom-bar button kept GONE by its hide toggle: not a visibility reference for ReloadHook's strip. */
    static boolean hiddenPlayerButton(View view) {
        if (!Toggles.on(Toggles.HIDE_PLAYER_FULLSCREEN)) {
            return false;
        }
        for (String name : PLAYER_BAR_IDS) {
            if (view.getId() == id(view.getContext(), name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Twitch leaves the chat input focused after the keyboard is closed (Back), so its
     * cursor blinks on until something else takes focus. Once the keyboard has been up
     * for the focused field and is gone again, the cursor is hidden until it's back;
     * not while the emote picker (it replaces the keyboard) is open.
     */
    private static void applyIdleCursor(Activity activity, View decor) {
        // shortcut: no IME insets below API 30, there the cursor keeps blinking.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        boolean on = Toggles.on(Toggles.IDLE_INPUT_CURSOR);
        boolean keyboard = keyboardTop(decor) != Integer.MAX_VALUE;
        boolean picker = false;
        for (View view : findAll(activity, decor, "emote_picker")) {
            picker |= view.isShown();
        }
        for (View view : findAll(activity, decor, "message_input")) {
            if (!(view instanceof TextView)) {
                continue;
            }
            TextView field = (TextView) view;
            boolean focused = field.hasFocus();
            if (focused && keyboard) {
                field.setTag(IME_SEEN_TAG_KEY, Boolean.TRUE);
            } else if (!focused) {
                field.setTag(IME_SEEN_TAG_KEY, null);
            }
            boolean idle = on && focused && !keyboard && !picker && field.getTag(IME_SEEN_TAG_KEY) != null;
            if (idle && field.isCursorVisible()) {
                field.setCursorVisible(false);
                field.setTag(CURSOR_TAG_KEY, Boolean.TRUE);
            } else if (!idle && field.getTag(CURSOR_TAG_KEY) != null) {
                field.setTag(CURSOR_TAG_KEY, null);
                field.setCursorVisible(true);
            }
        }
    }

    /** Whether "Vertical view" draws its row of buttons (VerticalOverlayPatch, at the composable's entry). */
    public static boolean hideVerticalButtons() {
        return Toggles.on(Toggles.HIDE_VERTICAL_BUTTONS);
    }

    /** Whether "Vertical view" draws the panel under its chat (VerticalOverlayPatch). */
    public static boolean hideVerticalPanel() {
        return Toggles.on(Toggles.HIDE_VERTICAL_PANEL);
    }

    /** A label of the player's layout picker (VerticalOverlayPatch): empty leaves only its icon. */
    public static String layoutLabel(String label) {
        return Toggles.on(Toggles.LAYOUT_PICKER_ICONS) ? "" : label;
    }

    /**
     * The points button's presenter state (31.9.0 {@code qb9}) hides the balance when
     * landscape or the keyboard is open; with the toggle on it never hears about landscape.
     */
    public static boolean pointsLandscape(boolean landscape) {
        return landscape && !Toggles.on(Toggles.POINTS_BALANCE_LANDSCAPE);
    }

    /**
     * From the link router (31.8.0 {@code qz3.d}/{@code f}): its "check the link" flag;
     * false opens the link without the "This link will take you to ..." dialog.
     */
    public static boolean linkDisclaimer(boolean check) {
        return check && !Toggles.on(Toggles.NO_LINK_DISCLAIMER);
    }

    /**
     * 31.x has no separate Unfollow on the channel page: it's {@code follow_button} while
     * following, told apart by Twitch's own "Following. Tap to unfollow" description.
     * In the player it's the extended metadata's "Following" button.
     */
    private static void applyUnfollow(Activity activity, View decor) {
        boolean on = Toggles.on(Toggles.HIDE_UNFOLLOW);
        boolean follow = Toggles.on(Toggles.HIDE_PROFILE_FOLLOW);
        boolean subscribe = Toggles.on(Toggles.HIDE_PROFILE_SUBSCRIBE);
        boolean gift = Toggles.on(Toggles.HIDE_PROFILE_GIFT);
        if (on) {
            hideAll(activity, decor, "unfollow_button", "extended_follow_button_container");
        }
        boolean any = on || follow || subscribe || gift;
        if (!any && !unfollowTouched) {
            return;
        }
        unfollowTouched = any;
        if (unfollowLabel == null) {
            int res = res(activity, "unfollow_talkback", "string");
            unfollowLabel = res != 0 ? activity.getString(res) : "";
            res = res(activity, "gift_a_sub", "string");
            giftLabel = res != 0 ? activity.getString(res) : "";
        }
        for (View button : findAll(activity, decor, "follow_button")) {
            CharSequence description = button.getContentDescription();
            boolean following = !unfollowLabel.isEmpty() && description != null && unfollowLabel.contentEquals(description);
            // Follow (not yet following) is the channel page's own toggle; unfollow is everywhere.
            hideWhile(following ? on : follow && onChannelPage(activity, button), button);
        }
        // The channel page's Subscribe and Gift A Sub are one view, told apart by its text.
        for (View button : findAll(activity, decor, "subscribe_button")) {
            if (!onChannelPage(activity, button)) {
                continue;
            }
            View label = find(activity, button, "subscribe_button_text");
            CharSequence text = label instanceof TextView ? ((TextView) label).getText() : null;
            boolean isGift = text != null && !giftLabel.isEmpty() && giftLabel.contentEquals(text);
            hideWhile(isGift ? gift : subscribe, button);
        }
    }

    private static String giftLabel = "";

    /**
     * ProfileShelvesPatch: Twitch's answer to "React Native channel page?" (experiment Android-Ultralight-Profile),
     * asked only once the React Native shell is on (not on tablets or logged out), or the viewer's pick.
     */
    public static boolean channelPageRn(boolean twitch) {
        int mode = LandingSettings.getChannelPageMode();
        return mode == 0 ? twitch : mode == 2;
    }

    /** ProfileShelvesPatch: a shelf of the native channel page's Home tab, by its key, that the viewer hid. */
    public static boolean profileShelfHidden(String key) {
        return "streamRecapsShelfKey".equals(key) && Toggles.on(Toggles.HIDE_PROFILE_RECAPS)
                || "categoryShelfKey".equals(key) && Toggles.on(Toggles.HIDE_PROFILE_CATEGORIES)
                || "streamerShelfKey".equals(key) && Toggles.on(Toggles.HIDE_PROFILE_STREAMERS);
    }

    /** ProfileShelvesPatch: the native Home tab's clip shelf builder, featured or "clips by user". */
    public static boolean profileClipShelfHidden(boolean featured) {
        return Toggles.on(featured ? Toggles.HIDE_PROFILE_FEATURED_CLIPS : Toggles.HIDE_PROFILE_USER_CLIPS);
    }

    /**
     * NO_MINI_PLAYER, MINI_PLAYER_SWIPE_CLOSE: presses the mini player's own ✕ under {@code mini} (draggable_layout),
     * once per second at most. It's a ButtonIcon: the click listener is on its inner view.
     */
    static void closeMiniPlayer(View mini) {
        View close = mini.findViewById(id(mini.getContext(), "minimized_overlay_close"));
        final View target = close != null ? clickableIn(close) : null;
        Object last = mini.getTag(MINI_CLOSE_TAG_KEY);
        long now = android.os.SystemClock.uptimeMillis();
        if (target == null || last instanceof Long && now - (Long) last < 1000) {
            return;
        }
        mini.setTag(MINI_CLOSE_TAG_KEY, now);
        target.post(target::performClick);
    }

    private static View clickableIn(View view) {
        if (view.isClickable()) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = clickableIn(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** DrawerSortPatch: the tablet's followed channels column keeps its own sort while SEPARATE_DRAWER_SORT is on. */
    public static String drawerSortKey(String key) {
        return Toggles.on(Toggles.SEPARATE_DRAWER_SORT) ? "retwitch_drawer_sort_method" : key;
    }

    /** In the channel page's action row (profile_card_no_divider), not the player's buttons with the same ids. */
    private static boolean onChannelPage(Activity activity, View view) {
        int row = id(activity, "other_user_action_container");
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (((View) p).getId() == row) {
                return true;
            }
        }
        return false;
    }

    /**
     * The mature content warning before a stream: pressed with its own "Start watching",
     * as a tap would. Left alone while Twitch asks for age verification instead.
     */
    private static void answerStopSign(Activity activity, View decor) {
        for (String suffix : new String[]{"", "_horizontal"}) {
            for (View ok : findAll(activity, decor, "stop_sign_button_affirmative" + suffix)) {
                if (!ok.isShown()) {
                    ok.setTag(PRESSED_TAG_KEY, null);
                    continue;
                }
                View age = ok.getParent() instanceof View
                        ? find(activity, (View) ok.getParent(), "stop_sign_button_age_verify" + suffix) : null;
                View target = clickTarget(ok);
                if ((age != null && age.isShown()) || ok.getTag(PRESSED_TAG_KEY) != null || target == null) {
                    continue;
                }
                ok.setTag(PRESSED_TAG_KEY, Boolean.TRUE);
                target.post(target::performClick);
            }
        }
    }

    /** Twitch's kit Button hands its listener to an inner MaterialButton; the view that has it. */
    private static View clickTarget(View view) {
        if (view.hasOnClickListeners()) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View target = clickTarget(group.getChildAt(i));
                if (target != null) {
                    return target;
                }
            }
        }
        return null;
    }

    /**
     * One Chat (fullscreen chat over the video): its input, and in its layout (around
     * {@code split_button}) the "Send a message" half of the pill and the typing buttons.
     * {@code emote_picker} and {@code message_input} exist elsewhere too (the expanded chat
     * inside One Chat), hence only the pill's children and siblings.
     */
    private static void hideOneChatInput(Activity activity, View decor) {
        hideAll(activity, decor, "one_chat_message_input_view_container");
        for (View pill : findAll(activity, decor, "split_button")) {
            hide(find(activity, pill, "divider"));
            hide(find(activity, pill, "message_input"));
            ViewGroup layout = pill.getParent() instanceof ViewGroup ? (ViewGroup) pill.getParent() : null;
            for (int i = 0; layout != null && i < layout.getChildCount(); i++) {
                View child = layout.getChildAt(i);
                for (String id : ONE_CHAT_INPUT) {
                    if (child.getId() == id(activity, id)) {
                        hide(child);
                    }
                }
            }
        }
    }

    /**
     * The "+" floats over the middle of the bar, not over its own menu cell: with a tab
     * hidden on one side it would sit off its gap. Centred child of a FrameLayout, so
     * left minus right margin shifts it; converges back to none once the cell is centred.
     */
    private static void centreCreateButton(Activity activity, View decor, boolean tabHidden) {
        View button = find(activity, decor, "custom_create_bottom_nav_button");
        View nav = find(activity, decor, "viewer_landing_bottom_navigation");
        View cell = nav != null ? find(activity, nav, "viewer_bottom_nav_create_placeholder") : null;
        if (button == null || cell == null || !button.isShown() || !cell.isShown()
                || !(button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) button.getLayoutParams();
        int shift = lp.leftMargin - lp.rightMargin;
        if (!tabHidden && shift == 0) {
            return;
        }
        int[] loc = new int[2];
        cell.getLocationInWindow(loc);
        int miss = loc[0] + cell.getWidth() / 2;
        button.getLocationInWindow(loc);
        miss -= loc[0] + button.getWidth() / 2;
        if (Math.abs(miss) <= 1) {
            return;
        }
        shift += miss;
        lp.leftMargin = Math.max(0, shift);
        lp.rightMargin = Math.max(0, -shift);
        button.setLayoutParams(lp);
    }

    /**
     * The points balance under the icon instead of beside it, so the input gets the width: each view
     * takes the layout params of its template from {@code PointsBalancePatch}, and gets its own back.
     * The template leaves the tree first: two views can't share params, they carry the solver's widget.
     */
    private static void stackPoints(Activity activity, View decor, boolean stacked) {
        for (View button : findAll(activity, decor, "community_points_button_layout")) {
            for (String name : new String[]{"icon_animation", "icon", "total"}) {
                View view = find(activity, button, "community_points_" + name);
                if (view == null) {
                    continue;
                }
                ViewGroup.LayoutParams[] params = (ViewGroup.LayoutParams[]) view.getTag(POINTS_PARAMS_TAG_KEY);
                if (params == null) {
                    View template = stacked ? find(activity, button, "rt_stacked_" + name) : null;
                    if (template == null) {
                        continue;
                    }
                    params = new ViewGroup.LayoutParams[]{view.getLayoutParams(), template.getLayoutParams()};
                    ((ViewGroup) button).removeView(template);
                    view.setTag(POINTS_PARAMS_TAG_KEY, params);
                }
                ViewGroup.LayoutParams wanted = params[stacked ? 1 : 0];
                if (view.getLayoutParams() != wanted) {
                    view.setLayoutParams(wanted);
                    if (view instanceof TextView) {
                        // 13 is the layout's own; the button has 36dp of height, 13sp doesn't fit under the icon.
                        ((TextView) view).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, stacked ? 11 : 13);
                    }
                }
            }
        }
    }

    /**
     * Chat column: optional header hiding (portrait too: the bar under the player with
     * Subscribe/Gift), landscape input hiding, and a usable landscape input while typing.
     */
    private static void applyLandscapeChat(Activity activity, View decor) {
        boolean land = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean header = ChatHeaderSetting.hidden(land);
        boolean input = Toggles.on(land ? Toggles.HIDE_LANDSCAPE_CHAT_INPUT : Toggles.HIDE_PORTRAIT_CHAT_INPUT);
        stackPoints(activity, decor, land && Toggles.on(Toggles.POINTS_BALANCE_LANDSCAPE)
                && Toggles.on(Toggles.POINTS_BALANCE_UNDER_ICON));
        // The theatre lives in this activity: another one on top (settings) doesn't mean it closed.
        if ("ViewerLandingActivity".equals(activity.getClass().getSimpleName())) {
            List<View> theatres = findAll(activity, decor, "draggable_container");
            PlayerHook.onTheatre(!theatres.isEmpty());
            for (View theatre : theatres) {
                PlayerGestures.wrapDragArea(theatre);
                ChatOff.apply(theatre);
            }
            navUnderKeyboard(activity, decor, theatres);
        }
        // Container of the chat mode snackbar ("Changed to No Chat" + "Next Mode") and Twitch's
        // "try chat overlay later" tooltip; Twitch never toggles its visibility, so GONE sticks.
        boolean modePopup = Toggles.on(Toggles.HIDE_CHAT_MODE_POPUP);
        if (modePopup || chatModeTouched) {
            chatModeTouched = modePopup;
            for (View bar : findAll(activity, decor, "chat_mode_snackbar")) {
                hideWhile(modePopup, bar);
            }
        }
        // Twitch's one-time "Chat Overlay" callout (one_chat_callout) shows, and switches the landscape chat to the
        // overlay, while this flag in its theatre prefs is false; any of its buttons sets it. Off again, it stays set.
        if (modePopup && !overlayCalloutMarked) {
            overlayCalloutMarked = true;
            activity.getSharedPreferences("video_quality", Context.MODE_PRIVATE).edit()
                    .putBoolean("pref_onechat_education_dialog_shown", true).apply();
        }
        // Header: its row's elements one by one; an emptied row takes the whole header (and the
        // 1dp line under it) away. Judged by visibility flags, not size: a GONE header isn't laid
        // out, so sizes would keep it hidden after Twitch shows its buttons again.
        boolean title = Toggles.on(Toggles.HIDE_CHAT_TITLE);
        boolean buttons = Toggles.on(Toggles.HIDE_HEADER_BUTTONS);
        for (View box : findAll(activity, decor, "chat_header_container")) {
            hideWhile(title, find(activity, box, "chat_name"));
            hideWhile(buttons, find(activity, box, "chat_header_buttons_container"));
            View row = find(activity, box, "header_container");
            hideWhile(header || row != null && !hasContent(row), box);
        }
        // A video's chat (chomments_view): the "Chat Replay" bar and the 1dp line above it; Twitch never touches either.
        boolean replayHeader = Toggles.on(Toggles.HIDE_VOD_CHAT_HEADER);
        if (replayHeader || replayHeaderTouched) {
            replayHeaderTouched = replayHeader;
            for (View bar : findAll(activity, decor, "chomments_header_container")) {
                hideWhile(replayHeader, bar);
                ViewGroup column = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
                int index = column != null ? column.indexOfChild(bar) : -1;
                View line = index > 0 ? column.getChildAt(index - 1) : null;
                if (line != null && line.getClass() == View.class) {
                    hideWhile(replayHeader, line);
                }
            }
        }
        // Tree walks only while hiding, plus one pass after, to put the views back.
        if (input || sideChatTouched) {
            sideChatTouched = input;
            for (String name : SIDE_CHAT) {
                for (View column : findAll(activity, decor, name)) {
                    hideWhile(input, find(activity, column, "chat_message_input_view_container"));
                    if (input) {
                        hideInputPlaceholder(find(activity, column, "chat_view_container"));
                    }
                }
            }
        }
        // Both ids are in TYPING_HIDDEN: the typing loop below must not bring them back.
        boolean noPoints = Toggles.on(land ? Toggles.HIDE_POINTS_LANDSCAPE : Toggles.HIDE_POINTS_PORTRAIT);
        if (noPoints || pointsTouched) {
            pointsTouched = noPoints;
            hideWhileAll(activity, decor, noPoints, "community_points_button_container");
            hideWhileAll(activity, decor, noPoints, "right_side_community_points_button_container");
        }
        // Typing: the fullscreen theatre doesn't resize for the keyboard (it covered
        // the input), and the input row left the field ~23 px. While the field has
        // focus and the keyboard or the emote panel is up: chat at half the screen, no points/menu
        // buttons beside the field, column padded above the keyboard.
        // (Not the IME's fullscreen editor: with Twitch's field it stopped updating.)
        // The emote button only while typing, in portrait too (bug #58: the setting was landscape-only); landscape is
        // the typing loop below.
        boolean emotePortrait = !land && Toggles.on(Toggles.EMOTE_BUTTON_TYPING_ONLY);
        if (emotePortrait || emotePortraitTouched) {
            emotePortraitTouched = emotePortrait;
            boolean pickerOpen = false;
            for (View view : findAll(activity, decor, "emote_picker")) {
                pickerOpen |= view.isShown();
            }
            boolean ime = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || keyboardTop(decor) != Integer.MAX_VALUE;
            emotePanelOpen = emotePortrait && pickerOpen;
            for (String name : SIDE_CHAT) {
                for (View column : findAll(activity, decor, name)) {
                    View field = find(activity, column, "message_input");
                    boolean draft = field instanceof TextView && field.isShown() && ((TextView) field).length() > 0;
                    boolean writing = field != null && (draft || (pickerOpen ? field.isShown() : ime && field.hasFocus()));
                    View emote = find(activity, column, "emoticon_picker");
                    hideWhile(emotePortrait && !writing, emote);
                    if (emotePortrait) {
                        keyboardAfterPanel(emote, field);
                    }
                }
            }
        }
        boolean typing = land && Toggles.on(Toggles.LANDSCAPE_INPUT_VISIBLE);
        boolean panels = land && Toggles.on(Toggles.WIDE_CHAT_PANELS);
        if (!typing && !panels && !typingTouched) {
            return;
        }
        typingTouched = typing || panels;
        int keyboardTop = typing ? keyboardTop(decor) : Integer.MAX_VALUE;
        // shortcut: no IME insets below API 30, so there it stays wide while the field has focus.
        boolean keyboard = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || keyboardTop != Integer.MAX_VALUE;
        boolean anyFocused = false;
        int[] loc = new int[2];
        boolean emoteTypingOnly = typing && Toggles.on(Toggles.EMOTE_BUTTON_TYPING_ONLY);
        boolean picker = false;
        for (View view : findAll(activity, decor, "emote_picker")) {
            picker |= view.isShown();
        }
        emotePanelOpen = typing && picker;
        for (String name : SIDE_CHAT) {
            for (View column : findAll(activity, decor, name)) {
                View field = find(activity, column, "message_input");
                // The emote panel replaces the keyboard and can open without the field having focus: still typing,
                // or the field shrinks back and can't show what was picked or be tapped to get the keyboard.
                // So is unsent text: it brings the send button, and a narrow column left the field 0 px.
                boolean draft = field instanceof TextView && field.isShown() && ((TextView) field).length() > 0;
                boolean focused = typing && field != null
                        && (draft || (picker ? field.isShown() : keyboard && field.hasFocus()));
                anyFocused |= focused;
                View emote = find(activity, column, "emoticon_picker");
                hideWhile(emoteTypingOnly && !focused, emote);
                if (typing) {
                    keyboardAfterPanel(emote, field);
                }
                remeasureInputRow(field);
                for (String id : TYPING_HIDDEN) {
                    hideWhile(focused || noPoints && id.contains("community_points"), find(activity, column, id));
                }
                column.getLocationInWindow(loc);
                int bottom = focused ? Math.max(0, loc[1] + column.getHeight() - keyboardTop) : 0;
                if (column.getPaddingBottom() != bottom) {
                    column.setPadding(column.getPaddingLeft(), column.getPaddingTop(), column.getPaddingRight(), bottom);
                }
            }
        }
        // The keyboard window stops at the display cutout and the nav rail showed through that strip.
        // INVISIBLE: GONE would widen the content behind the theatre.
        hideWhile(anyFocused, find(activity, decor, "viewer_landing_navigation_rail"), View.INVISIBLE);
        View panel = panels ? openChatPanel(activity, decor) : null;
        PanelBack.update(activity, panel);
        TheatreDividerHook.setTyping(decor, anyFocused || panel != null);
    }

    /** The emote panel is open in the landscape side chat; the last pass's answer, read when the button is touched. */
    private static boolean emotePanelOpen;

    /**
     * Twitch's emote button closes the panel it opened and leaves nothing to type with: the chat would just
     * shrink. In the side chat it switches back to the keyboard instead. The listener only watches: Twitch's
     * click still runs, and the keyboard comes after it.
     */
    private static void keyboardAfterPanel(View emote, View field) {
        if (emote == null || field == null || emote.getTag(EMOTE_TOUCH_TAG_KEY) != null) {
            return;
        }
        emote.setTag(EMOTE_TOUCH_TAG_KEY, Boolean.TRUE);
        emote.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP && emotePanelOpen) {
                field.postDelayed(() -> {
                    field.requestFocus();
                    android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                            field.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (keyboard != null) {
                        keyboard.showSoftInput(field, 0);
                    }
                }, 150);
            }
            return false;
        });
    }

    /**
     * In a narrow column (the points balance takes the room) the chat's width animation can end with the input
     * row at its final width and its children still laid out for a smaller one: the field 0 px wide, the emote
     * button at the row's start, until something else asks for a layout. One try per occurrence: a row that
     * can't be fixed must not turn every pass into another layout.
     */
    private static void remeasureInputRow(View field) {
        if (field == null || !(field.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup row = (ViewGroup) field.getParent();
        int room = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child != field && child.getVisibility() != View.GONE) {
                room -= child.getWidth();
            }
        }
        if (field.getWidth() >= room) {
            field.setTag(REMEASURED_TAG_KEY, null);
        } else if (field.getTag(REMEASURED_TAG_KEY) == null) {
            field.setTag(REMEASURED_TAG_KEY, Boolean.TRUE);
            field.requestLayout();
        }
    }

    /** A poll or prediction on screen inside a side chat column (not the overlay chat's highlights). */
    private static View openChatPanel(Activity activity, View decor) {
        android.graphics.Rect visible = new android.graphics.Rect();
        for (String name : CHAT_PANELS) {
            for (View panel : findAll(activity, decor, name)) {
                // Visible rect: a collapsed highlight may keep its expanded frame VISIBLE at zero height (layout XML).
                if (!panel.isShown() || !panel.getGlobalVisibleRect(visible)) {
                    continue;
                }
                for (String chat : SIDE_CHAT) {
                    for (View column : findAll(activity, decor, chat)) {
                        if (isAncestor(column, panel)) {
                            return panel;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Saved height of the bottom navigation squashed by {@link #navUnderKeyboard}. */
    private static final int NAV_HEIGHT_TAG_KEY = 0x7e700d6c;
    /** Saved width of a player button squashed by {@link #squashWhile}. */
    private static final int SQUASH_TAG_KEY = 0x7e700d6e;

    /**
     * Feedback #37: the keyboard lifts the whole landing layout (its bottom padding), the bottom navigation with it,
     * so a chat input in a navigation page (a channel page's Chat tab) sat a navigation bar above the keys. While the
     * keyboard is up for a field outside the theatre, the navigation is 1 px tall and transparent. Not GONE: the
     * input then went under the keyboard. Not 0: in this ConstraintLayout that means "match constraints", and the bar
     * stretched over half the screen.
     */
    private static void navUnderKeyboard(Activity activity, View decor, List<View> theatres) {
        View nav = find(activity, decor, "viewer_landing_bottom_navigation_group");
        if (nav == null || nav.getLayoutParams() == null) {
            return;
        }
        View focus = activity.getCurrentFocus();
        boolean squash = nav.getVisibility() == View.VISIBLE && focus instanceof android.widget.EditText
                && keyboardTop(decor) != Integer.MAX_VALUE;
        for (View theatre : theatres) {
            if (squash && (theatre == focus || isAncestor(theatre, focus))) {
                squash = false;
            }
        }
        Object saved = nav.getTag(NAV_HEIGHT_TAG_KEY);
        ViewGroup.LayoutParams lp = nav.getLayoutParams();
        if (squash && saved == null) {
            nav.setTag(NAV_HEIGHT_TAG_KEY, lp.height);
            lp.height = 1;
            nav.setLayoutParams(lp);
            nav.setAlpha(0f);
        } else if (!squash && saved instanceof Integer) {
            nav.setTag(NAV_HEIGHT_TAG_KEY, null);
            lp.height = (Integer) saved;
            nav.setLayoutParams(lp);
            nav.setAlpha(1f);
        }
    }

    /**
     * Window y of the keyboard's top, or MAX_VALUE when it's hidden.
     * shortcut: IME insets need API 30; below that the input stays covered.
     */
    private static int keyboardTop(View decor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Integer.MAX_VALUE;
        }
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null || !insets.isVisible(WindowInsets.Type.ime())) {
            return Integer.MAX_VALUE;
        }
        return decor.getHeight() - insets.getInsets(WindowInsets.Type.ime()).bottom;
    }

    /**
     * The React Native home's Turbo button ("following-turbo-upsell" on the
     * Following tab): RN puts a view's testID into {@link View#getTag()}.
     */
    private static void hideRnTurbo(Context context, View decor) {
        if (scanned(context, decor, true)) {
            for (View view : scanTurbo) {
                hide(view);
            }
        }
    }

    private static boolean isRnTurbo(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).contains("turbo-upsell");
    }

    /**
     * Hides a bottom-navigation item through its menu: a GONE item view would
     * leave its slot empty, a hidden menu item makes the bar re-lay out.
     */
    private static void hideNavItem(Activity activity, View decor, String itemName) {
        int itemId = id(activity, itemName);
        if (itemId == 0) {
            return;
        }
        for (String navName : NAV_VIEWS) {
            View nav = find(activity, decor, navName);
            Menu menu = nav != null ? menuOf(nav) : null;
            MenuItem item = menu != null ? menu.findItem(itemId) : null;
            if (item != null && item.isVisible()) {
                item.setVisible(false);
            }
        }
    }

    /** {@code NavigationBarView.getMenu()}, found by return type: Material's method names may be renamed. */
    private static Menu menuOf(View nav) {
        try {
            Class<?> cls = nav.getClass();
            Method getter = MENU_GETTERS.get(cls);
            if (getter == null && !MENU_GETTERS.containsKey(cls)) {
                for (Method m : cls.getMethods()) {
                    if (m.getParameterTypes().length == 0 && Menu.class.isAssignableFrom(m.getReturnType())) {
                        getter = m;
                        break;
                    }
                }
                MENU_GETTERS.put(cls, getter);
            }
            return getter != null ? (Menu) getter.invoke(nav) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** View id by entry name, cached (getIdentifier is slow); shared by the layout-pass hooks. Main thread only. */
    public static int id(Context context, String idName) {
        Integer id = IDS.get(idName);
        if (id == null) {
            id = res(context, idName, "id");
            IDS.put(idName, id);
        }
        return id;
    }

    static int res(Context context, String name, String type) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        // Renamed package: resources keep Twitch's own package name.
        return id != 0 ? id : context.getResources().getIdentifier(name, type, "tv.twitch.android.app");
    }

    private static View find(Activity activity, View root, String idName) {
        int id = id(activity, idName);
        if (id == 0 || root == null) {
            return null;
        }
        // Same as findViewById: the first match in tree order.
        List<View> scanned = scanned(activity, root, false) ? scan.get(id) : null;
        if (scanned != null) {
            return scanned.isEmpty() ? null : scanned.get(0);
        }
        return root.findViewById(id);
    }

    /** Every view with that id under {@code root} (Twitch can have several players / chats alive). */
    public static List<View> findAll(Context activity, View root, String idName) {
        int id = id(activity, idName);
        List<View> scanned = id != 0 && scanned(activity, root, true) ? scan.get(id) : null;
        if (scanned != null) {
            return scanned;
        }
        List<View> out = new ArrayList<>();
        if (id != 0) {
            collect(root, id, out);
        }
        return out;
    }

    /**
     * Whether this pass's one-walk lookup covers {@code root} (the pass's decor), walking
     * the tree on the first lookup that needs it ({@code build}).
     */
    private static boolean scanned(Context context, View root, boolean build) {
        if (root == null || root != scanRoot) {
            return false;
        }
        if (scan == null && build) {
            SparseArray<List<View>> byId = new SparseArray<>();
            for (String[] names : new String[][]{SCANNED, FOLLOW_SUB, EXTENSIONS, CHANNEL_SKINS, NAV_VIEWS, SIDE_CHAT, CHAT_PANELS}) {
                for (String name : names) {
                    int id = id(context, name);
                    if (id != 0) {
                        byId.put(id, new ArrayList<View>());
                    }
                }
            }
            List<View> turbo = new ArrayList<>();
            walk(root, byId, turbo, false);
            scan = byId;
            scanTurbo = turbo;
        }
        return scan != null;
    }

    /** {@link #collect} for every scanned id at once, plus the RN Turbo views (not looked into either). */
    private static void walk(View view, SparseArray<List<View>> byId, List<View> turbo, boolean inTurbo) {
        List<View> same = byId.get(view.getId());
        if (same != null && (same.isEmpty() || !isAncestor(same.get(same.size() - 1), view))) {
            same.add(view);
        }
        if (!inTurbo && isRnTurbo(view)) {
            turbo.add(view);
            inTurbo = true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), byId, turbo, inTurbo);
            }
        }
    }

    /** Pre-order: only the last match so far can contain {@code view}. */
    private static boolean isAncestor(View ancestor, View view) {
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static void collect(View view, int id, List<View> out) {
        if (view.getId() == id) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), id, out);
            }
        }
    }

    private static void hideAll(Activity activity, View root, String... idNames) {
        for (String idName : idNames) {
            for (View v : findAll(activity, root, idName)) {
                hide(v);
            }
        }
    }

    private static void hide(View view) {
        if (view != null && view.getVisibility() == View.VISIBLE) {
            view.setVisibility(View.GONE);
            hiddenCount++;
        }
        if (view != null) {
            HIDDEN.add(view);
        }
    }

    /** A view hidden outside hide/hideWhile (ChatOff): watched the same way in case Twitch shows it again. */
    static void watchHidden(View view) {
        HIDDEN.add(view);
    }

    private static void hideWhileAll(Activity activity, View root, boolean hidden, String idName) {
        for (View view : findAll(activity, root, idName)) {
            hideWhile(hidden, view);
        }
    }

    /** Hides {@code view} while {@code hidden}; shows it again afterwards only if we hid it. */
    private static void hideWhile(boolean hidden, View view) {
        hideWhile(hidden, view, View.GONE);
    }

    private static void hideWhile(boolean hidden, View view, int visibility) {
        if (view == null) {
            return;
        }
        if (hidden) {
            if (view.getVisibility() == View.VISIBLE) {
                view.setVisibility(visibility);
                view.setTag(HIDDEN_TAG_KEY, Boolean.TRUE);
                hiddenCount++;
            }
            HIDDEN.add(view);
        } else if (view.getTag(HIDDEN_TAG_KEY) != null) {
            view.setTag(HIDDEN_TAG_KEY, null);
            HIDDEN.remove(view);
            if (view.getVisibility() == visibility) {
                view.setVisibility(View.VISIBLE);
            }
        }
    }
}
