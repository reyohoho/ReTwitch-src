package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Point;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings and placement for Twitch's five bottom menu items (#84). */
final class BottomTabs {
    static final String DEFAULT = "01234";
    private static final String[][] ITEM_IDS = {
            {"viewer_bottom_nav_home", "viewer_bottom_nav_home_rn", "viewer_bottom_nav_home_portal"},
            {"viewer_bottom_nav_explore"}, {"viewer_bottom_nav_create_placeholder"},
            {"viewer_bottom_nav_activity"}, {"viewer_bottom_nav_profile", "viewer_bottom_nav_profile_rn"}
    };

    private BottomTabs() {}

    static boolean valid(Object value) {
        if (!(value instanceof String)) return false;
        String order = (String) value;
        if (order.length() != DEFAULT.length()) return false;
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
        Editor editor = new Editor(c, order);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(editor);
        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle(Strings.pick("Порядок кнопок нижней панели", "Bottom bar button order"))
                .setView(scroll)
                .setPositiveButton(Strings.save(), (d, w) -> {
                    if (!order.equals(editor.order)) {
                        LandingSettings.setBottomOrder(c, editor.order);
                        screen.restartNeeded();
                    }
                })
                .setNegativeButton(Strings.cancel(), null)
                .setNeutralButton(Strings.reset(), null).show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> editor.showOrder(DEFAULT));
    }

    private static final class Editor extends LinearLayout {
        final String[] names = labels();
        String order;
        String beforeDrag;

        Editor(Context c, String initial) {
            super(c);
            setOrientation(VERTICAL);
            setOnDragListener((v, event) -> drag(event));
            showOrder(initial);
        }

        void showOrder(String value) {
            order = value;
            removeAllViews();
            int size = Math.round(48 * getResources().getDisplayMetrics().density);
            for (int i = 0; i < order.length(); i++) {
                String name = names[order.charAt(i) - '0'];
                LinearLayout row = new LinearLayout(getContext());
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = (TextView) View.inflate(getContext(), android.R.layout.simple_list_item_1, null);
                label.setText(name);
                row.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                TextView handle = new TextView(getContext());
                handle.setText("≡");
                handle.setTextSize(24);
                handle.setGravity(Gravity.CENTER);
                handle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                handle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        int x = handle.getLeft() + (int) event.getX();
                        int y = handle.getTop() + (int) event.getY();
                        View.DragShadowBuilder shadow = new View.DragShadowBuilder(row) {
                            @Override public void onProvideShadowMetrics(Point size, Point touch) {
                                super.onProvideShadowMetrics(size, touch);
                                touch.set(x, y);
                            }
                        };
                        return row.startDragAndDrop(null, shadow, row, 0);
                    }
                    return true;
                });
                row.addView(handle, new LayoutParams(size, size));
                row.setContentDescription(name);
                row.setFocusable(true);
                row.setOnClickListener(v -> {
                    // Position picker for keyboard and accessibility services.
                    PopupMenu slots = new PopupMenu(getContext(), row);
                    for (int j = 0; j < order.length(); j++) {
                        slots.getMenu().add(0, j, j, (j + 1) + ". " + names[order.charAt(j) - '0']);
                    }
                    slots.setOnMenuItemClickListener(item -> {
                        showOrder(move(order, indexOfChild(row), item.getItemId()));
                        return true;
                    });
                    slots.show();
                });
                addView(row);
            }
        }

        private boolean drag(DragEvent event) {
            if (!(event.getLocalState() instanceof View)) return false;
            View row = (View) event.getLocalState();
            int from = indexOfChild(row);
            if (from < 0) return false;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    beforeDrag = order;
                    row.setAlpha(0f); // Keep its slot while the system draws the drag shadow.
                    break;
                case DragEvent.ACTION_DRAG_LOCATION:
                case DragEvent.ACTION_DROP:
                    int to = getChildCount() - 1;
                    for (int i = 0; i < getChildCount(); i++) {
                        if (event.getY() < getChildAt(i).getBottom()) {
                            to = i;
                            break;
                        }
                    }
                    if (to != from) {
                        order = move(order, from, to);
                        removeViewAt(from);
                        addView(row, to);
                    }
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    row.setAlpha(1f);
                    if (!event.getResult()) showOrder(beforeDrag);
                    break;
            }
            return true;
        }
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
