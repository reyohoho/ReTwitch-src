package tv.retwitch.mod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** The WRAP_CONTENT metadata host immediately above the weighted portrait chat in player_view. */
public final class VodMetadataPanel extends FrameLayout {
    private static final String TAG = "RTVodMetadata";
    private int detailsId;
    private int stickyId;
    private ObjectAnimator fade;
    private int target;

    public VodMetadataPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private boolean overlaysChat() {
        if (!Toggles.on(Toggles.VOD_DETAILS_OVER_CHAT) || ChannelTracker.playerMinimized() || ChatOff.hidden()
                || getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT
                || !(getParent() instanceof LinearLayout)) {
            return false;
        }
        // Clips also use player_view. The adjacent weighted pane must actually host a VOD chat replay.
        ViewGroup column = (ViewGroup) getParent();
        View bottom = column.getChildAt(column.indexOfChild(this) + 1);
        return bottom != null && bottom.getId() == UiHider.id(getContext(), "bottom_container")
                && bottom.findViewById(UiHider.id(getContext(), "chomments_coordinator_layout")) != null;
    }

    @Override
    protected void onMeasure(int width, int height) {
        super.onMeasure(width, height);
        if (!(getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) getLayoutParams();
        // LinearLayout sums this child's measured height and margins immediately after measure() returns.
        // Leave room for persistent callouts; only transient stream details overlap the top of the chat.
        lp.bottomMargin = 0;
        try {
            if (overlaysChat()) {
                if (stickyId == 0) {
                    stickyId = UiHider.id(getContext(), "sticky_metadata_container");
                }
                View sticky = findViewById(stickyId);
                int keep = sticky != null && sticky.getVisibility() != GONE ? sticky.getMeasuredHeight() : 0;
                lp.bottomMargin = -Math.max(0, getMeasuredHeight() - keep);
            }
        } catch (Throwable t) {
            Log.w(TAG, "metadata measure failed", t);
            CrashReporter.report(TAG, "metadata measure failed", t);
        }
    }

    /** Replaces the delegate's setVisibility, including calls on unrelated shared delegate instances. */
    public static void setDelegateVisibility(View view, int visibility) {
        try {
            // The extended panel has its own root class in player_metadata_view_extended.xml.
            if (view != null && "tv.twitch.android.feature.theatre.NotifyTouchLinearLayout".equals(view.getClass().getName())) {
                for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
                    if (p instanceof VodMetadataPanel && ((VodMetadataPanel) p).show(view, visibility)) {
                        return;
                    }
                }
            }
            if (view != null) {
                view.setVisibility(visibility);
            }
        } catch (Throwable t) {
            Log.w(TAG, "metadata visibility failed", t);
            CrashReporter.report(TAG, "metadata visibility failed", t);
            if (view != null) {
                view.setVisibility(visibility);
            }
        }
    }

    private boolean show(View details, int visibility) {
        if (detailsId == 0) {
            detailsId = UiHider.id(getContext(), "player_metadata_container");
        }
        if (!(details.getParent() instanceof View) || ((View) details.getParent()).getId() != detailsId
                || !overlaysChat() || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            cancelFade();
            return false;
        }
        if (fade != null && target == visibility) {
            return true;
        }
        if (fade == null && (visibility == details.getVisibility() || !isLaidOut())) {
            return false;
        }
        float from = fade != null ? getAlpha() : visibility == VISIBLE ? 0f : 1f;
        cancelFade();
        if (visibility == VISIBLE) {
            details.setVisibility(VISIBLE);
        }
        target = visibility;
        setAlpha(from);
        fade = ObjectAnimator.ofFloat(this, View.ALPHA, visibility == VISIBLE ? 1f : 0f);
        fade.setDuration(160);
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (fade == animation) {
                    fade = null;
                    details.setVisibility(visibility);
                    setAlpha(1f);
                }
            }
        });
        fade.start();
        return true;
    }

    private void cancelFade() {
        if (fade != null) {
            ObjectAnimator old = fade;
            fade = null;
            old.cancel();
            setAlpha(1f);
        }
    }

    @Override protected void onDetachedFromWindow() {
        // Finish the requested visibility even when Android detaches a theatre mid-animation.
        if (fade != null) {
            fade.end();
        }
        super.onDetachedFromWindow();
    }
}
