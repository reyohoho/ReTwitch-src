package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

/** Sleep timer controls in the player and settings. */
public final class TimerHook {
    private static final String TAG = "RTTimer";
    private static volatile boolean toastedFailure;

    private TimerHook() {}

    /** A member of Twitch's overlay layout from inflation, including while its controls are hidden. */
    public static final class ClockButton extends ImageView implements ViewTreeObserver.OnPreDrawListener {
        private View gear;
        private View controls;
        private boolean enabled;
        private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (prefs, key) -> {
            try {
                enabled = TimerSettings.isButtonEnabled();
                invalidate();
            } catch (Throwable t) {
                onFailure(getContext(), t);
            }
        };
        public ClockButton(Context context, AttributeSet attrs) {
            super(context, attrs);
            try {
                enabled = TimerSettings.isButtonEnabled();
                setVisibility(enabled ? INVISIBLE : GONE);
                setTag("retwitch_timer_button");
                setImageDrawable(new ClockDrawable(dp(context, 24)));
                setScaleType(ScaleType.CENTER);
                setContentDescription(Strings.timerButtonTalkback());
                setFocusable(true);
                setBackground(borderlessBackground(context));
                setOnClickListener(v -> showTimerDialog(context));
            } catch (Throwable t) {
                setVisibility(GONE);
                onFailure(context, t);
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                if (!(getParent() instanceof ViewGroup)) return;
                ViewGroup overlay = (ViewGroup) getParent();
                gear = overlay.findViewById(UiHider.id(getContext(), "settings_button"));
                controls = overlay.findViewById(UiHider.id(getContext(), "player_overlay_background"));
                if (controls == null) controls = overlay; // The widget puts the background on the overlay itself.
                TimerSettings.prefs(getContext()).registerOnSharedPreferenceChangeListener(settingsChanged);
                enabled = TimerSettings.isButtonEnabled();
                getViewTreeObserver().addOnPreDrawListener(this);
            } catch (Throwable t) {
                onFailure(getContext(), t);
            }
        }

        @Override protected void onDetachedFromWindow() {
            try {
                TimerSettings.prefs(getContext()).unregisterOnSharedPreferenceChangeListener(settingsChanged);
                getViewTreeObserver().removeOnPreDrawListener(this);
            } catch (Throwable t) {
                onFailure(getContext(), t);
            }
            gear = null;
            controls = null;
            super.onDetachedFromWindow();
        }

        @Override public boolean onPreDraw() {
            try {
                View reference = gear != null && UiHider.hiddenByMod(gear) ? controls : gear;
                int next = !enabled || reference == null ? GONE
                        : reference.getVisibility() == VISIBLE ? VISIBLE : INVISIBLE;
                int was = getVisibility();
                if (was != next) setVisibility(next);
                if (next == VISIBLE && getAlpha() != reference.getAlpha()) setAlpha(reference.getAlpha());
                // Switching the feature changes the chain's width. Draw after the new layout.
                return (was == GONE) == (next == GONE);
            } catch (Throwable t) {
                onFailure(getContext(), t);
                return true;
            }
        }
    }

    /**
     * Shows the hours/minutes picker; on OK starts (or restarts) the countdown.
     * Public so the ReTwitch settings menu can open it too.
     */
    public static void showTimerDialog(final Context context) {
        try {
            if (context == null) {
                return;
            }
            int pad = dp(context, 20);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(pad, dp(context, 8), pad, 0);

            LinearLayout pickers = new LinearLayout(context);
            pickers.setOrientation(LinearLayout.HORIZONTAL);
            pickers.setGravity(Gravity.CENTER);

            final NumberPicker hours = new NumberPicker(context);
            hours.setMinValue(0);
            hours.setMaxValue(23);
            hours.setValue(TimerSettings.getLastHours());
            final NumberPicker minutes = new NumberPicker(context);
            minutes.setMinValue(0);
            minutes.setMaxValue(59);
            minutes.setValue(TimerSettings.getLastMinutes());

            pickers.addView(labeledPicker(context, hours, Strings.timerHoursSuffix()));
            pickers.addView(labeledPicker(context, minutes, Strings.timerMinutesSuffix()));
            content.addView(pickers);

            if (TimerController.isRunning()) {
                TextView hint = new TextView(context);
                hint.setText(Strings.timerRunningHint(TimerController.remaining()));
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                hint.setPadding(0, dp(context, 12), 0, 0);
                hint.setAlpha(0.7f);
                content.addView(hint);
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle(Strings.timerDialogTitle())
                    .setView(content)
                    .setPositiveButton(Strings.timerStart(), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            int h = hours.getValue();
                            int m = minutes.getValue();
                            int totalSeconds = h * 3600 + m * 60;
                            if (totalSeconds <= 0) {
                                TimerController.toast(context, Strings.timerCantSetZero());
                                return;
                            }
                            TimerSettings.setLastDuration(context, h, m);
                            TimerController.start(context, totalSeconds);
                            TimerController.toast(context,
                                    Strings.timerStartedToast(TimerController.formatTime(totalSeconds)));
                        }
                    })
                    .setNegativeButton(Strings.cancel(), null);

            if (TimerController.isRunning()) {
                builder.setNeutralButton(Strings.timerStop(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        TimerController.cancel();
                        TimerController.toast(context, Strings.timerCancelledToast());
                    }
                });
            }
            builder.show();
        } catch (Throwable t) {
            onFailure(context, t);
        }
    }

    private static LinearLayout labeledPicker(Context context, NumberPicker picker, String suffix) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int margin = dp(context, 8);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(margin, margin, margin, margin);
        column.setLayoutParams(lp);
        column.addView(picker);
        TextView label = new TextView(context);
        label.setText(suffix);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(context, 8), 0, 0);
        column.addView(label);
        return column;
    }

    private static Drawable borderlessBackground(Context context) {
        try {
            android.util.TypedValue outValue = new android.util.TypedValue();
            context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true);
            if (outValue.resourceId != 0) {
                return context.getResources().getDrawable(outValue.resourceId, context.getTheme());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static void onFailure(Context context, Throwable t) {
        Log.w(TAG, "timer hook failure", t);
        CrashReporter.report(TAG, "timer hook failure", t);
        if (toastedFailure) {
            return;
        }
        toastedFailure = true;
        try {
            Context c = context != null ? context : AppContext.get();
            if (c != null) {
                Toast.makeText(c, Strings.timerFailedToast(), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * A simple white clock face so we don't have to inject a drawable resource
     * (the extension dex is code-only): a ring with an hour and a minute hand.
     */
    private static final class ClockDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;

        ClockDrawable(int sizePx) {
            this.sizePx = sizePx;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float radius = Math.min(b.width(), b.height()) / 2f;
            if (radius <= 0f) {
                return;
            }
            float stroke = Math.max(2f, radius * 0.12f);
            paint.setStrokeWidth(stroke);
            float ring = radius - stroke;
            canvas.drawCircle(cx, cy, ring, paint);
            // Minute hand (up), hour hand (right) -- roughly "10:10"-ish is
            // hard without trig; keep it readable at "12 and 3".
            canvas.drawLine(cx, cy, cx, cy - ring * 0.62f, paint);
            canvas.drawLine(cx, cy, cx + ring * 0.45f, cy, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
