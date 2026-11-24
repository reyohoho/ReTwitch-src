package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Settings and placement for Twitch's five bottom menu items (#84). */
final class BottomTabs {
    static final String DEFAULT = "01234";
    private static final String[][] ITEM_IDS = {
            {"viewer_bottom_nav_home", "viewer_bottom_nav_home_rn", "viewer_bottom_nav_home_portal"},
            {"viewer_bottom_nav_explore"}, {"viewer_bottom_nav_create_placeholder"},
            {"viewer_bottom_nav_activity"}, {"viewer_bottom_nav_profile", "viewer_bottom_nav_profile_rn"}
    };

    private BottomTabs() {}

    static boolean valid(String order) {
        if (order == null || order.length() != DEFAULT.length()) return false;
        for (int i = 0; i < DEFAULT.length(); i++) if (order.indexOf(DEFAULT.charAt(i)) < 0) return false;
        return true;
    }

    static String move(String order, int from, int to) {
        StringBuilder result = new StringBuilder(order);
        char item = result.charAt(from);
        result.deleteCharAt(from);
        return result.insert(to, item).toString();
    }

    private static String[] labels() {
        return new String[]{Strings.pick("Главная", "Home"), Strings.pick("Просмотр", "Browse"),
                Strings.pick("Создать", "Create"), Strings.pick("Активность", "Activity"), Strings.pick("Профиль", "Profile")};
    }

    static void edit(SettingsScreen screen) {
        Context c = screen.context;
        String order = LandingSettings.bottomOrder();
        String[] names = labels(), slots = new String[names.length];
        for (int i = 0; i < slots.length; i++) slots[i] = (i + 1) + ". " + names[order.charAt(i) - '0'];
        AlertDialog.Builder dialog = new AlertDialog.Builder(c)
                .setTitle(Strings.pick("Какую кнопку переместить?", "Which button do you want to move?"))
                .setItems(slots, (d, from) -> new AlertDialog.Builder(c)
                        .setTitle(Strings.pick("На какое место?", "Move to which position?"))
                        .setItems(slots, (pick, to) -> {
                            if (from != to) {
                                LandingSettings.setBottomOrder(c, move(order, from, to));
                                screen.restartNeeded();
                            }
                        }).setNegativeButton(Strings.cancel(), null).show())
                .setNegativeButton(Strings.cancel(), null);
        if (!DEFAULT.equals(order)) dialog.setNeutralButton(Strings.reset(), (d, w) -> {
            LandingSettings.setBottomOrder(c, DEFAULT);
            screen.restartNeeded();
        });
        dialog.show();
    }

    static View item(View nav, int tab) {
        if (nav == null) return null;
        for (String name : ITEM_IDS[tab]) {
            int id = UiHider.id(nav.getContext(), name);
            View v = id == 0 ? null : nav.findViewById(id);
            if (v != null && v.getVisibility() == View.VISIBLE) return v;
        }
        return null;
    }

    /** Keep Material's children, menu IDs, selected states and click handlers. Translate only visible cells. */
    static void arrange(View nav, String order) {
        if (nav == null || !nav.isShown()) return;
        View home = item(nav, 0);
        if (home == null || !(home.getParent() instanceof ViewGroup)) return;
        ViewGroup menu = (ViewGroup) home.getParent();
        boolean rtl = menu.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int edge = rtl ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                edge = rtl ? Math.max(edge, child.getRight()) : Math.min(edge, child.getLeft());
            }
        }
        int previous = View.NO_ID;
        for (int i = 0; i < order.length(); i++) {
            View v = item(nav, order.charAt(i) - '0');
            if (v == null || v.getParent() != menu) continue;
            if (rtl) edge -= v.getWidth();
            int dx = edge - v.getLeft();
            if (v.getTranslationX() != dx) v.setTranslationX(dx);
            if (v.getAccessibilityTraversalAfter() != previous) v.setAccessibilityTraversalAfter(previous);
            previous = v.getId();
            if (!rtl) edge += v.getWidth();
        }
    }
}
