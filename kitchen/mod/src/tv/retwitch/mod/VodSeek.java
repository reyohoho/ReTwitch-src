package tv.retwitch.mod;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * VOD seek step (ReVive's VodSeek): Twitch's VOD buttons jump -10 / +30 s.
 * {@code VodPlayerPatch} passes those literals through here -- the overlay's
 * per-tap seconds (Twitch sums the taps, labels the sum and seeks once) and
 * the media session's milliseconds -- and gets the chosen steps back.
 * The buttons' icons have 10 and 30 drawn in: {@link #showStep} puts the step
 * over copies of them without the digits.
 */
public final class VodSeek {

    private static final int SHOWN_TAG_KEY = 0x7e700d68;

    private VodSeek() {
    }

    /** Injected after the overlay buttons' -10 / +30 literals. Never throws. */
    public static int stepSeconds(int twitchSeconds) {
        try {
            return twitchSeconds < 0 ? -PlayerSettings.getSeekBackSeconds() : PlayerSettings.getSeekForwardSeconds();
        } catch (Throwable t) {
            return twitchSeconds;
        }
    }

    /** Injected after the media session's -10000 / +30000 ms literals (notification, headset): their own steps. Never throws. */
    public static int stepMillis(int twitchMillis) {
        try {
            int seconds = PlayerSettings.getNotificationSeekSeconds(twitchMillis > 0);
            return (twitchMillis < 0 ? -seconds : seconds) * 1000;
        } catch (Throwable t) {
            return twitchMillis;
        }
    }

    private static int notificationStep(String action) {
        boolean forward = "fast_forward".equals(action);
        return forward || "rewind".equals(action) ? PlayerSettings.getNotificationSeekSeconds(forward) : 0;
    }

    /** Before the shade's seek actions (VodPlayerPatch): the label says the step. Never throws. */
    public static String notificationLabel(String action, String label) {
        try {
            int seconds = notificationStep(action);
            return seconds > 0 ? Strings.seekStep("fast_forward".equals(action), seconds) : label;
        } catch (Throwable t) {
            return label;
        }
    }

    /**
     * Before the shade's seek actions: a step other than Twitch's 10 / 30 gets the icon without digits. The shade takes
     * an icon resource, so the step itself can't be drawn there. Never throws.
     */
    public static int notificationIcon(String action, int icon) {
        try {
            boolean forward = "fast_forward".equals(action);
            int seconds = notificationStep(action);
            if (seconds == 0 || seconds == (forward ? 30 : 10)) {
                return icon;
            }
            Context context = AppContext.get();
            int plain = context == null ? 0 : context.getResources().getIdentifier(
                    forward ? "rt_seek_forward" : "rt_seek_rewind", "drawable", context.getPackageName());
            return plain != 0 ? plain : icon;
        } catch (Throwable t) {
            return icon;
        }
    }

    private static final String PIP_BACK = "tv.retwitch.mod.PIP_SEEK_BACK";
    private static final String PIP_FORWARD = "tv.retwitch.mod.PIP_SEEK_FORWARD";
    private static volatile boolean pipListening;

    /**
     * The native theatre's picture-in-picture actions (VodPlayerPatch, before {@code setActions}): Twitch's play/pause
     * alone. A VOD gets back and forward around it, the notification's steps drawn on them (#58). Never throws.
     */
    public static List<Object> pipActions(List<Object> actions) {
        try {
            Context context = AppContext.get();
            if (actions == null || actions.size() != 1 || context == null || !Toggles.on(Toggles.PIP_SEEK)
                    || ChannelTracker.getCurrentVodId() == null || LiveRecord.playerLogin() != null) {
                return actions;
            }
            listenPip(context);
            List<Object> out = new ArrayList<>(3);
            out.add(pipAction(context, false));
            out.add(actions.get(0));
            out.add(pipAction(context, true));
            return out;
        } catch (Throwable t) {
            Log.w("RTVodSeek", "pipActions failed", t);
            return actions;
        }
    }

    private static RemoteAction pipAction(Context context, boolean forward) {
        int seconds = PlayerSettings.getPipSeekSeconds(forward);
        int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int arrow = context.getResources().getIdentifier(forward ? "rt_seek_forward" : "rt_seek_rewind", "drawable",
                context.getPackageName());
        Drawable icon = new LayerDrawable(arrow != 0
                ? new Drawable[]{context.getDrawable(arrow), new Digits(String.valueOf(seconds))}
                : new Drawable[]{new Digits(String.valueOf(seconds))});
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(bitmap));
        String label = Strings.seekStep(forward, seconds);
        PendingIntent intent = PendingIntent.getBroadcast(context, forward ? 2 : 1,
                new Intent(forward ? PIP_FORWARD : PIP_BACK).setPackage(context.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithBitmap(bitmap), label, label, intent);
    }

    private static void listenPip(Context context) {
        if (pipListening) {
            return;
        }
        pipListening = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean forward = PIP_FORWARD.equals(intent.getAction());
                try {
                    Object player = PlayerHook.currentPlayer();
                    if (player == null) {
                        return;
                    }
                    long position = (Long) player.getClass().getMethod("getPosition").invoke(player);
                    long step = PlayerSettings.getPipSeekSeconds(forward) * 1000L;
                    PlayerHook.seekCurrent(Math.max(0, position + (forward ? step : -step)));
                } catch (Throwable t) {
                    Log.w("RTVodSeek", "pip seek failed", t);
                }
            }
        };
        IntentFilter filter = new IntentFilter(PIP_BACK);
        filter.addAction(PIP_FORWARD);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /** For {@code seek_forward_button} / {@code seek_rewind_button} on every {@link UiHider} pass; the stock step keeps Twitch's icon. */
    static void showStep(View button, boolean forward) {
        int seconds = forward ? PlayerSettings.getSeekForwardSeconds() : PlayerSettings.getSeekBackSeconds();
        Object shown = button.getTag(SHOWN_TAG_KEY);
        if (shown == null ? seconds == (forward ? 30 : 10) : shown.equals(seconds)) {
            return;
        }
        button.setTag(SHOWN_TAG_KEY, seconds);
        Context context = button.getContext();
        int icon = context.getResources().getIdentifier(forward ? "rt_seek_forward" : "rt_seek_rewind", "drawable",
                context.getPackageName());
        if (icon != 0 && button instanceof ImageView) {
            ((ImageView) button).setImageDrawable(new LayerDrawable(new Drawable[]{
                    context.getDrawable(icon), new Digits(String.valueOf(seconds))}));
            button.setContentDescription(Strings.seekStep(forward, seconds));
        }
    }

    /** The step where Twitch's digits stand, inside the arrow; three digits get narrower to stay in it. */
    private static final class Digits extends Drawable {
        private final String text;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect ink = new Rect();

        Digits(String text) {
            this.text = text;
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect box = getBounds();
            paint.setTextSize(box.height() * 0.49f);
            float most = box.width() * 0.6f;
            float width = paint.measureText(text);
            if (width > most) {
                paint.setTextSize(paint.getTextSize() * most / width);
            }
            paint.getTextBounds(text, 0, text.length(), ink);
            canvas.drawText(text, box.exactCenterX(), box.top + box.height() * 0.49f - ink.exactCenterY(), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
