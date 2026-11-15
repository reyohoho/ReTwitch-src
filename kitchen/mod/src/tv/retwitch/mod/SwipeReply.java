package tv.retwitch.mod;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.TextView;

/**
 * Swipe a live chat message left to reply to it ({@link Toggles#SWIPE_REPLY}, issue #28, like Discord): the same
 * ReplyToMessageEvent as the message menu's "Reply" ({@link ReplyThreads#openTray}). The row's text view carries the
 * row model; its touch listener (EmoteChatHook's) hands every event here first. The row follows the finger and springs
 * back; past {@link #TRIGGER_DP} on release it replies.
 */
public final class SwipeReply {

    private static final int ROW_TAG_KEY = 0x7e700d76;
    private static final float TRIGGER_DP = 64;
    private static final float MAX_DP = 96;

    // One finger on one row at a time: the chat list is touched on the main thread only.
    private static float downX;
    private static float downY;
    private static boolean decided;
    private static boolean armed;
    private static boolean past;

    private SwipeReply() {
    }

    /** From the message text's bind: the row model while the toggle is on, else the row is left alone. */
    static void bind(TextView text, Object row) {
        boolean on = row != null && Toggles.on(Toggles.SWIPE_REPLY);
        text.setTag(ROW_TAG_KEY, on ? row : null);
        if (on) {
            tv.retwitch.mod.emotes.EmoteChatHook.tapTargets(text);
        }
    }

    /** True while the swipe is ours: Twitch's own gesture got a CANCEL when it started. Never throws. */
    public static boolean onTouch(View v, MotionEvent e) {
        try {
            Object row = v.getTag(ROW_TAG_KEY);
            if (row == null) {
                return false;
            }
            View moved = v.getParent() instanceof View ? (View) v.getParent() : v;
            float density = v.getResources().getDisplayMetrics().density;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    decided = false;
                    armed = false;
                    past = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    if (!armed) {
                        float dy = e.getRawY() - downY;
                        int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                        if (decided || Math.abs(dx) < slop && Math.abs(dy) < slop) {
                            return false;
                        }
                        decided = true;
                        if (dx > 0 || Math.abs(dx) < 2 * Math.abs(dy)) {
                            return false; // a scroll or a right swipe: not ours
                        }
                        armed = true;
                        ViewParent parent = v.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        v.onTouchEvent(cancel); // no long-press menu, no profile
                        cancel.recycle();
                    }
                    moved.setTranslationX(Math.max(-MAX_DP * density, Math.min(0, dx)));
                    boolean nowPast = dx <= -TRIGGER_DP * density;
                    if (nowPast && !past) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    past = nowPast;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!armed) {
                        return false;
                    }
                    armed = false;
                    moved.animate().translationX(0).setDuration(150).start();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && past) {
                        ReplyThreads.openTray(v.getContext(), row);
                    }
                    return true;
                default:
                    return armed;
            }
        } catch (Throwable t) {
            armed = false;
            android.util.Log.w("RTSwipeReply", "touch failed", t);
            return false;
        }
    }
}
