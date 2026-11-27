package tv.retwitch.mod;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

/** Keep the two-finger gesture in the portrait video pane even as its video is scaled underneath the fingers. */
final class PortraitVideoZoom {
    private static final int ZOOMED_VIDEO = 0x7e700c72;
    private static final int MAX_SCALE = 4;
    private boolean consuming;
    private View video;
    private float initialSpan, initialScale, anchorX, anchorY, initialX, initialY, startX, startY;

    boolean onTouch(Activity activity, Window.Callback original, MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            consuming = false;
            video = null;
        }
        int lifted = action == MotionEvent.ACTION_POINTER_UP ? ev.getActionIndex() : -1;
        boolean newPair = action == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() == 2
                || consuming && lifted >= 0 && ev.getPointerCount() == 3;
        if (newPair) {
            video = null;
            if (activity.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT
                    || !Toggles.on(Toggles.FIX_PORTRAIT_ZOOM)) return consuming;
            int first = pointerAfterLift(0, lifted), second = pointerAfterLift(1, lifted);
            View decor = activity.getWindow().getDecorView();
            View pane = activity.findViewById(UiHider.id(activity, "player_pane"));
            Rect bounds = new Rect();
            if (pane == null || !pane.getGlobalVisibleRect(bounds) || PlayerGestures.minimizedTheatre(activity, pane)
                    || !bounds.contains((int) ev.getX(first), (int) ev.getY(first))
                    || !bounds.contains((int) ev.getX(second), (int) ev.getY(second))
                    || PlayerGestures.sheetAt(activity, ev.getRawX(), ev.getRawY())) return consuming;
            View target = pane.findViewById(UiHider.id(activity, "playback_view_container"));
            initialSpan = span(ev, first, second);
            if (target == null || !target.isShown() || target.getWidth() == 0 || target.getHeight() == 0
                    || initialSpan < 1) return consuming;
            if (!consuming) {
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                try {
                    original.dispatchTouchEvent(cancel);
                } finally {
                    cancel.recycle();
                }
            }
            video = target;
            consuming = true;
            initialScale = video.getScaleX();
            initialX = video.getTranslationX();
            initialY = video.getTranslationY();
            startX = (ev.getX(first) + ev.getX(second)) / 2;
            startY = (ev.getY(first) + ev.getY(second)) / 2;
            int[] at = new int[2];
            video.getLocationInWindow(at);
            anchorX = (startX - at[0]) / initialScale - video.getPivotX();
            anchorY = (startY - at[1]) / initialScale - video.getPivotY();
            decor.setTag(ZOOMED_VIDEO, video);
        } else if (!consuming) {
            return false;
        } else if (action == MotionEvent.ACTION_MOVE && video != null && ev.getPointerCount() == 2) {
            if (activity.getWindow().getDecorView().getTag(ZOOMED_VIDEO) != video) {
                video = null;
                return true;
            }
            float scale = scale(initialScale, initialSpan, span(ev, 0, 1));
            float dx = (ev.getX(0) + ev.getX(1)) / 2 - startX;
            float dy = (ev.getY(0) + ev.getY(1)) / 2 - startY;
            video.setScaleX(scale);
            video.setScaleY(scale);
            video.setTranslationX(offset(initialX + dx + anchorX * (initialScale - scale), video.getWidth(), scale));
            video.setTranslationY(offset(initialY + dy + anchorY * (initialScale - scale), video.getHeight(), scale));
        } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_POINTER_DOWN) {
            video = null;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            consuming = false;
            video = null;
            View decor = activity.getWindow().getDecorView();
            View target = (View) decor.getTag(ZOOMED_VIDEO);
            if (target != null && target.getScaleX() == 1f) decor.setTag(ZOOMED_VIDEO, null);
        }
        return true;
    }

    // ACTION_POINTER_UP still includes the finger that just left in its coordinate array.
    static int pointerAfterLift(int index, int lifted) {
        return lifted >= 0 && index >= lifted ? index + 1 : index;
    }

    private static float span(MotionEvent ev, int first, int second) {
        return (float) Math.hypot(ev.getX(first) - ev.getX(second), ev.getY(first) - ev.getY(second));
    }

    static float scale(float initial, float from, float to) {
        return Math.max(1f, Math.min(MAX_SCALE, initial * to / from));
    }

    static float offset(float translation, int size, float scale) {
        float limit = size * (scale - 1f) / 2;
        return Math.max(-limit, Math.min(limit, translation));
    }

    static void checkLayout(Activity activity, View decor) {
        View target = (View) decor.getTag(ZOOMED_VIDEO);
        if (target == null) return;
        if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                && target.isShown() && target.isAttachedToWindow() && Toggles.on(Toggles.FIX_PORTRAIT_ZOOM)
                && !PlayerGestures.minimizedTheatre(activity, target)) return;
        decor.setTag(ZOOMED_VIDEO, null);
        target.setScaleX(1f);
        target.setScaleY(1f);
        target.setTranslationX(0f);
        target.setTranslationY(0f);
    }
}
