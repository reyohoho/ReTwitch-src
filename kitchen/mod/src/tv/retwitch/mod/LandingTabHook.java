package tv.retwitch.mod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
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

    private static PipFeedWatch pipExit;
    private static final String[] FEED_PAGES = {"following-panel", "live-feed-page", "clips-feed-page"};

    public static void systemPipChanged(Activity activity, boolean inPip) {
        pipExit = inPip ? null : new PipFeedWatch(activity);
    }

    /** Called on actual layouts, including Fabric commits well after the mini player has settled. */
    static void checkPipFeed(Activity activity, View decor) {
        PipFeedWatch watch = pipExit;
        if (watch == null || watch.owner.get() != activity || watch.pending || watch.repairs >= 3) return;
        if (!ChannelTracker.playerMinimized()) {
            if (watch.wasMinimized) pipExit = null;
            return;
        }
        watch.wasMinimized = true;
        try {
            View shortView = watch.shortView(decor);
            if (shortView == null) return;
            watch.pending = true;
            watch.expectedAtLayout.set(watch.expected);
            watch.coveredAtLayout.set(watch.covered);
            decor.postDelayed(() -> {
                watch.pending = false;
                if (pipExit != watch || activity.isInPictureInPictureMode() || !ChannelTracker.playerMinimized()) return;
                try {
                    View current = watch.shortView(decor);
                    if (current == null) return;
                    if (current != shortView || !watch.expectedAtLayout.equals(watch.expected)
                            || !watch.coveredAtLayout.equals(watch.covered)) {
                        checkPipFeed(activity, decor); // The window is still resizing. Confirm its new bounds first.
                        return;
                    }
                    View surface = watch.surface.get();
                    int w = surface.getWidth(), h = surface.getHeight();
                    if (w <= 1 || h <= 1) return;
                    watch.repairs++;
                    Log.i(TAG, "PiP feed repair " + watch.repairs + ": " + current.getClass().getSimpleName()
                            + " " + watch.covered.toShortString() + ", viewport " + watch.expected.toShortString());
                    // Change only height: a width pulse also shifts the horizontal pager's active page.
                    surface.forceLayout();
                    surface.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h - 1, View.MeasureSpec.EXACTLY));
                    surface.postOnAnimation(() -> {
                        surface.forceLayout();
                        surface.requestLayout();
                    });
                } catch (Throwable t) {
                    watch.failed(t);
                }
            }, 100);
        } catch (Throwable t) {
            watch.failed(t);
        }
    }

    private static final class PipFeedWatch {
        final WeakReference<Activity> owner;
        WeakReference<View> surface = new WeakReference<>(null), pager = new WeakReference<>(null);
        WeakReference<View> page = new WeakReference<>(null);
        int pageIndex = -1, repairs;
        boolean pending, wasMinimized;
        final Rect expected = new Rect(), covered = new Rect();
        final Rect expectedAtLayout = new Rect(), coveredAtLayout = new Rect();
        final int[] pagerLocation = new int[2], pageLocation = new int[2];

        PipFeedWatch(Activity activity) { owner = new WeakReference<>(activity); }

        void failed(Throwable t) {
            if (pipExit == this) pipExit = null;
            Log.w(TAG, "PiP feed check failed", t);
            CrashReporter.report(TAG, "PiP feed check failed", t);
        }

        View shortView(View decor) throws Exception {
            View root = surface.get(), tabs = pager.get();
            if (root == null || tabs == null || !tabs.isAttachedToWindow() || root.getRootView() != decor) {
                Object fragment = rnHome == null ? null : rnHome.get();
                root = fragment == null ? null : (View) fragment.getClass().getMethod("getView").invoke(fragment);
                tabs = root == null ? null : root.findViewWithTag("home-tabs-pager");
                if (tabs == null || root.getRootView() != decor) return null;
                surface = new WeakReference<>(root);
                pager = new WeakReference<>(tabs);
                pageIndex = -1;
            }
            if (!tabs.isShown() || tabs.getWidth() == 0 || !root.getGlobalVisibleRect(expected)) return null;
            // The activity decor includes Twitch's native navigation. The visible RN surface is the actual
            // home viewport in fullscreen, split screen and letterboxed windows alike.
            WindowInsets insets = root.getRootWindowInsets();
            int margin = Math.max(2, Math.round(4 * root.getResources().getDisplayMetrics().density));
            int horizontal = margin + (insets == null ? 0 : insets.getSystemWindowInsetLeft() + insets.getSystemWindowInsetRight());
            int bottom = margin + (insets == null ? 0 : insets.getSystemWindowInsetBottom());
            if (shortBounds(tabs, horizontal, bottom)) return tabs;
            for (ViewParent p = tabs.getParent(); p instanceof View && p != root; p = p.getParent()) {
                View parent = (View) p;
                // RN can clip its Canvas with clipChildren false. Inspect visible bounds only for a
                // short ancestor; healthy ancestors need no additional coordinate-tree traversal.
                if ((expected.width() - parent.getWidth() > horizontal || expected.height() - parent.getHeight() > bottom)
                        && shortBounds(parent, horizontal, bottom)) return parent;
            }
            // The pager and stack may already fill their viewport while Fabric still clips just the active page.
            int width = tabs.getWidth(), scroll = tabs.getScrollX();
            int index = Math.round(scroll / (float) width);
            if (index < 0 || index >= FEED_PAGES.length || Math.abs(scroll - (long) index * width) > 2) return null;
            View panel = page.get();
            if (index != pageIndex || panel == null || !panel.isAttachedToWindow()) {
                panel = tabs.findViewWithTag(FEED_PAGES[index]);
                pageIndex = index;
                page = new WeakReference<>(panel);
            }
            if (panel == null || !panel.isShown()) return null;
            tabs.getLocationInWindow(pagerLocation);
            panel.getLocationInWindow(pageLocation);
            if (Math.abs(pageLocation[0] - pagerLocation[0]) > horizontal) return null; // offscreen or mid-swipe
            return shortBounds(panel, horizontal, bottom) ? panel : null;
        }

        private boolean shortBounds(View v, int horizontal, int bottom) {
            if (v.getWidth() == 0 || v.getHeight() == 0 || !v.getGlobalVisibleRect(covered)) return false;
            // A header above the pager can legitimately move its top down. Its bottom should still reach
            // the bottom of the viewport. getGlobalVisibleRect also accounts for clipping by an ancestor.
            return clippedFeed(expected.width(), expected.bottom, covered.width(), covered.bottom, horizontal, bottom);
        }
    }

    static boolean clippedFeed(int viewportWidth, int viewportBottom, int visibleWidth, int visibleBottom,
            int horizontalAllowance, int bottomAllowance) {
        return visibleWidth > 0 && (viewportWidth - visibleWidth > horizontalAllowance
                || viewportBottom - visibleBottom > bottomAllowance);
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
