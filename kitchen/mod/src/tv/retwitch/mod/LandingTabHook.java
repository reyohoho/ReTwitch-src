package tv.retwitch.mod;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import java.lang.ref.WeakReference;

/**
 * Decides which discovery-feed pager tab is selected by default on a normal
 * app launch.
 *
 * <p>Twitch builds the pager pages as {@code [Following, Live, Clips]} and
 * uses a small "default position" provider that returns {@code 1} (Live) as
 * the fallback when no specific page was requested (deeplink/push still pass
 * their own index, which is left untouched). A one-instruction patch on that
 * provider (see {@code LandingTabPatch.kt}) routes its result through here.
 *
 * <p>When the ReTwitch toggle is on (the default) we return {@code 0}, the
 * Following page's fixed index in that list, so the app lands on Following.
 * When off, we return whatever Twitch originally computed.
 *
 * <p>This is called directly from injected smali with no surrounding
 * try/catch, so -- per the mod's fail-soft rule -- any error here must be
 * swallowed and the original value returned rather than propagated into
 * Twitch (a throw here would crash the whole main screen on launch).
 */
public final class LandingTabHook {

    private static final String TAG = "RTlandingtab";

    private static boolean firstActivity = true;
    private static int pendingSlot = -1;
    private static final String VIEWER = "tv.twitch.android.feature.viewer.landing.ViewerLandingActivity";

    /** The launcher forwards to the viewer. Deep links, notifications and restored tasks keep their own routes. */
    static void activityCreated(Activity activity, Bundle saved) {
        try {
            if (!firstActivity) {
                if (saved != null || !VIEWER.equals(activity.getClass().getName())) pendingSlot = -1;
                return;
            }
            firstActivity = false;
            if (saved != null || !"tv.twitch.android.app.core.LandingActivity".equals(activity.getClass().getName())) return;
            Intent intent = activity.getIntent();
            if (intent == null || intent.getData() != null || (intent.getAction() != null &&
                    !Intent.ACTION_MAIN.equals(intent.getAction())) || intent.hasExtra("destinationOrdinal") ||
                    intent.getBooleanExtra("fromPushNotification", false) || intent.getBooleanExtra("fromDeepLink", false)) return;
            switch (LandingSettings.launchTab()) {
                case LandingSettings.BROWSE: if (!Toggles.on(Toggles.HIDE_TAB_BROWSE)) pendingSlot = 1; break;
                case LandingSettings.ACTIVITY: if (!Toggles.on(Toggles.HIDE_TAB_ACTIVITY)) pendingSlot = 3; break;
                case LandingSettings.PROFILE: if (!Toggles.on(Toggles.HIDE_TAB_PROFILE)) pendingSlot = 4; break;
            }
        } catch (Throwable t) {
            pendingSlot = -1;
            Log.w(TAG, "launch destination failed", t);
            CrashReporter.report(TAG, "launch destination failed", t);
        }
    }

    static boolean pendingBottomTab() {
        return pendingSlot >= 0;
    }

    static void openBottomTab(Activity activity, View nav) {
        if (!VIEWER.equals(activity.getClass().getName()) || nav == null || !nav.isShown() || nav.getWidth() == 0 ||
                BottomTabs.item(nav, 0) == null) return;
        View item = BottomTabs.item(nav, pendingSlot);
        pendingSlot = -1;
        if (item != null) item.post(() -> {
            try {
                if (item.isAttachedToWindow() && item.isShown() && !activity.isFinishing()) item.performClick();
            } catch (Throwable t) {
                Log.w(TAG, "opening launch tab failed", t);
                CrashReporter.report(TAG, "opening launch tab failed", t);
            }
        });
    }

    private LandingTabHook() {
    }

    /**
     * Injected right before the discovery-feed view state is constructed with
     * {@code (position, z, z, z, pages, ...)}: returns the index of the
     * Following page ({@code DiscoveryFeedPage$FollowingPage}, a public model
     * class) when the toggle is on -- its position changed between Twitch
     * versions, so it's looked up rather than hardcoded. Never throws.
     */
    public static int resolveDefaultPosition(int original, boolean a, boolean b, boolean c, java.util.List<?> pages) {
        try {
            if (LandingSettings.isFollowingDefaultEnabled()) {
                for (int i = 0; pages != null && i < pages.size(); i++) {
                    Object page = pages.get(i);
                    if (page != null && page.getClass().getName().endsWith("$FollowingPage")) {
                        return i;
                    }
                }
                // The initial state is built with an empty page list: Following is the first tab.
                return 0;
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveDefaultPosition failed, keeping original tab", t);
            CrashReporter.report(TAG, "resolveDefaultPosition failed, keeping original tab", t);
        }
        return original;
    }

    private static final String RN_TAB_KEY = "tv.twitch.android.rn.feed.subfeedTab";
    private static WeakReference<Object> rnHome;
    /** The RN home opens on Following: the Live content the launch splash waits for won't come. */
    private static volatile int readyPage = -1;

    private static WeakReference<Activity> checkHomeAfterPip;

    private static boolean pipCheckPosted;

    /** A VOD can keep covering the home after PiP expands. Check once the underlying feed is visible again. */
    public static void systemPipChanged(Activity activity, boolean inPip) {
        checkHomeAfterPip = inPip ? null : new WeakReference<>(activity);
        pipCheckPosted = false;
    }

    static void checkPipFeed(Activity activity, View decor) {
        WeakReference<Activity> exit = checkHomeAfterPip;
        if (exit == null || exit.get() != activity || pipCheckPosted || !ChannelTracker.playerMinimized()) return;
        pipCheckPosted = true;
        decor.postOnAnimation(() -> checkPipFeed(activity, decor, exit, 0));
    }

    private static void checkPipFeed(Activity activity, View decor, WeakReference<Activity> exit, int pass) {
        if (exit != checkHomeAfterPip) return;
        try {
            Object fragment = rnHome == null ? null : rnHome.get();
            View surface = fragment == null ? null : (View) fragment.getClass().getMethod("getView").invoke(fragment);
            View pager = surface == null ? null : surface.findViewWithTag("home-tabs-pager");
            if (activity.isInPictureInPictureMode() || !ChannelTracker.playerMinimized() || pager == null
                    || !pager.isShown() || pager.getWidth() == 0 || pager.getHeight() == 0
                    || surface.getRootView() != decor || !decor.isAttachedToWindow()) {
                // The fragment may not be attached/shown on this layout pass. Preserve the exit for its next layout.
                pipCheckPosted = false;
                return;
            }
            if (pipSizedFeed(decor.getWidth(), decor.getHeight(), pager.getWidth(), pager.getHeight())) {
                // An unchanged MeasureSpec leaves Fabric's mounted children at the PiP size.
                // Measure one pixel smaller, then let the real parent restore both exact constraints.
                int w = surface.getWidth(), h = surface.getHeight();
                if (w > 1 && h > 1) {
                    surface.forceLayout();
                    surface.measure(View.MeasureSpec.makeMeasureSpec(w - 1, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h - 1, View.MeasureSpec.EXACTLY));
                    surface.postOnAnimation(() -> {
                        surface.forceLayout();
                        surface.requestLayout();
                    });
                }
                Log.i(TAG, "PiP feed repair " + pass + ": pager " + pager.getWidth() + "x" + pager.getHeight()
                        + ", surface " + surface.getWidth() + "x" + surface.getHeight());
            }
            // Window, RN surface and Fabric update on separate frames. Catch a stale measurement delivered later.
            if (pass < 2) {
                decor.postDelayed(() -> checkPipFeed(activity, decor, exit, pass + 1), pass == 0 ? 180 : 450);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "PiP feed remeasure failed", t);
            CrashReporter.report(TAG, "PiP feed remeasure failed", t);
        }
        checkHomeAfterPip = null;
        pipCheckPosted = false;
    }

    // #82: the 1080px-wide phone displayed a ~590px-wide, ~166px-high feed after leaving PiP.
    static boolean pipSizedFeed(int hostWidth, int hostHeight, int pageWidth, int pageHeight) {
        return pageWidth > 0 && pageHeight > 0 && pageWidth * 4L < hostWidth * 3L && pageHeight * 3L < hostHeight;
    }

    /** Injected at the entry of {@code DiscoveryFeedRNFragment.onCreateView}. Never throws. */
    public static void onRnHomeCreated(Object fragment) {
        rnHome = new WeakReference<>(fragment);
    }

    /** Injected after the React Native home reads its starting tab argument (31.x). Never throws. */
    public static String resolveRnTab(String tab) {
        try {
            int start = LandingSettings.launchTab();
            if (start != LandingSettings.FOLLOWING && start != LandingSettings.CLIPS) return tab;
            String desired = start == LandingSettings.CLIPS ? "clips" : "following";
            // Native passes its default page explicitly ("live").
            if (tab == null || "live".equals(tab)) {
                // The argument too: a re-attached home view (back from settings) re-emits it when it
                // differs from the last tab sent, and "live" would switch the home to Live.
                Object fragment = rnHome != null ? rnHome.get() : null;
                Bundle args = fragment != null
                        ? (Bundle) fragment.getClass().getMethod("getArguments").invoke(fragment) : null;
                if (args != null) {
                    args.putString(RN_TAB_KEY, desired);
                }
                tab = desired;
            }
            readyPage = "following".equals(tab) ? 0 : "clips".equals(tab) ? 2 : -1;
        } catch (Throwable t) {
            Log.w(TAG, "resolveRnTab failed, keeping original tab", t);
            CrashReporter.report(TAG, "resolveRnTab failed, keeping original tab", t);
        }
        return tab;
    }

    /** Injected before the content-ready holder's getters return: what the launch splash waits for. */
    public static boolean feedReady(boolean ready) {
        return ready || readyPage >= 0 && pageShown(readyPage);
    }

    /** The RN pager lays the tabs out side by side, Following first: mounted and at 0. */
    private static boolean pageShown(int page) {
        try {
            Object fragment = rnHome != null ? rnHome.get() : null;
            View root = fragment != null ? (View) fragment.getClass().getMethod("getView").invoke(fragment) : null;
            HorizontalScrollView pager = root != null ? findPager(root) : null;
            return pager != null && pager.getWidth() > 0 && pager.getScrollX() == page * pager.getWidth();
        } catch (Throwable t) {
            Log.w(TAG, "pager check failed", t);
            return false;
        }
    }

    /** Depth first: the category chips scroll sideways too, but inside the pager. */
    private static HorizontalScrollView findPager(View view) {
        if (view instanceof HorizontalScrollView) {
            HorizontalScrollView scroll = (HorizontalScrollView) view;
            if (scroll.getChildCount() > 0 && scroll.getChildAt(0).getWidth() >= 2 * scroll.getWidth()) {
                return scroll;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                HorizontalScrollView pager = findPager(group.getChildAt(i));
                if (pager != null) {
                    return pager;
                }
            }
        }
        return null;
    }
}
