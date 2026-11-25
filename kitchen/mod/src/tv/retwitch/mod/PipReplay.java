package tv.retwitch.mod;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

/** Uses Twitch's VOD Replay control in system PiP. State comes from the seekable overlay, including muted playback. */
public final class PipReplay {
    private static final int OWNER_TAG = 0x7e700d86;
    private static final String ACTION = "tv.retwitch.mod.PIP_REPLAY." + UUID.randomUUID();
    private static final WeakHashMap<Object, Controls> controls = new WeakHashMap<>();
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static List<RemoteAction> stock;
    private static int center = -1;
    private static Controls finished;
    private static long generation;
    private static RemoteAction replacement;
    private static boolean listening;
    private static long shownGeneration;

    private static final View.OnAttachStateChangeListener ATTACHMENT = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {
            Controls c = (Controls) v.getTag(OWNER_TAG);
            if (c != null && c.button.get() == v && c.vod && c.replay) finish(c);
        }

        @Override public void onViewDetachedFromWindow(View v) {
            if (finished != null && finished.button.get() == v) finish(null);
        }
    };

    private PipReplay() {}

    private static final class Controls {
        WeakReference<View> button = new WeakReference<>(null);
        boolean vod;
        boolean replay;
    }

    private static Controls controls(Object presenter) {
        Controls result = controls.get(presenter);
        if (result == null) {
            result = new Controls();
            controls.put(presenter, result);
        }
        return result;
    }

    /** VOD and clip initialization are separate presenter methods. Either starts a fresh playback session. */
    public static void bind(Object presenter, boolean vod) {
        try {
            Controls c = controls(presenter);
            c.vod = vod;
            c.replay = false;
            if (finished == c) finish(null);
        } catch (Throwable t) {
            CrashReporter.report("RTPipReplay", "bind", t);
        }
    }

    public static void attach(Object presenter, View root) {
        try {
            Controls c = controls(presenter);
            if (finished == c) finish(null);
            View button = root.findViewById(UiHider.id(root.getContext(), "replay_button"));
            c.button = new WeakReference<>(button);
            c.replay = false;
            if (button != null) {
                if (button.getTag(OWNER_TAG) == null) button.addOnAttachStateChangeListener(ATTACHMENT);
                button.setTag(OWNER_TAG, c);
            }
        } catch (Throwable t) {
            CrashReporter.report("RTPipReplay", "attach", t);
        }
    }

    /** Immediately after Twitch applies PLAY / PAUSE / REPLAY to this view, independently of overlay visibility. */
    public static void state(View button, boolean replay) {
        try {
            Object tag = button.getTag(OWNER_TAG);
            if (!(tag instanceof Controls)) return;
            Controls c = (Controls) tag;
            if (c.button.get() != button || !c.vod) return;
            c.replay = replay;
            if (replay && button.isAttachedToWindow()) finish(c);
            else if (finished == c) finish(null);
        } catch (Throwable t) {
            CrashReporter.report("RTPipReplay", "state", t);
        }
    }

    private static void finish(Controls c) {
        if (finished == c) return;
        finished = c;
        generation++;
        // Twitch can change the playback model and its overlay in the same UI turn.
        Activity activity = host.get();
        if (activity != null && stock != null) activity.getWindow().getDecorView().post(PipReplay::refresh);
    }

    private static View target(Activity activity) {
        if (finished == null || activity == null) return null;
        View button = finished.button.get();
        return button != null && button.isAttachedToWindow() && button.getVisibility() == View.VISIBLE &&
                button.getRootView() == activity.getWindow().getDecorView() ? button : null;
    }

    /** Twitch's replay button dispatches Restart followed by ShowControls. System PiP supplies its own controls. */
    public static List<?> replayActions(List<?> actions) {
        try {
            Activity activity = host.get();
            if (actions.size() == 2 && target(activity) != null && activity.isInPictureInPictureMode()
                    && Toggles.on(Toggles.PIP_REPLAY)) {
                return Collections.singletonList(actions.get(0));
            }
        } catch (Throwable t) {
            Log.w("RTPipReplay", "replayActions", t);
            CrashReporter.report("RTPipReplay", "replayActions", t);
        }
        return actions;
    }

    /** Runs after the optional PiP seek controls have been added. Keep every other action in its original position. */
    public static List<RemoteAction> actions(List<RemoteAction> list, Activity activity) {
        try {
            host = new WeakReference<>(activity);
            replacement = null;
            stock = null;
            center = -1;
            if (list == null || list.isEmpty() || !Toggles.on(Toggles.PIP_REPLAY)) return list;
            Context c = activity;
            int play = c.getResources().getIdentifier("play_action", "string", c.getPackageName());
            int pause = c.getResources().getIdentifier("pause_action", "string", c.getPackageName());
            for (int i = 0; i < list.size(); i++) {
                CharSequence title = list.get(i).getTitle();
                if (play != 0 && c.getString(play).contentEquals(title) || pause != 0 && c.getString(pause).contentEquals(title)) {
                    center = i;
                    stock = new ArrayList<>(list);
                    return target(activity) != null ? withReplay(c) : list;
                }
            }
        } catch (Throwable t) {
            CrashReporter.report("RTPipReplay", "actions", t);
        }
        return list;
    }

    private static List<RemoteAction> withReplay(Context c) {
        Context app = c.getApplicationContext();
        if (!listening) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    try {
                        Activity activity = host.get();
                        View button = target(activity);
                        if (button != null && activity.isInPictureInPictureMode() && Toggles.on(Toggles.PIP_REPLAY)
                                && generation == intent.getLongExtra("session", -1)) {
                            // Twitch's own action cancels autoplay, seeks this VOD to zero and starts its player.
                            button.performClick();
                        }
                    } catch (Throwable t) {
                        CrashReporter.report("RTPipReplay", "replay", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(receiver, filter);
            listening = true;
        }
        Intent intent = new Intent(ACTION).setPackage(c.getPackageName()).putExtra("session", generation);
        PendingIntent tap = PendingIntent.getBroadcast(app, (int) generation, intent, PendingIntent.FLAG_IMMUTABLE);
        int icon = c.getResources().getIdentifier("ic_icon_replay", "drawable", c.getPackageName());
        String label = Strings.pick("Повторить", "Replay");
        replacement = new RemoteAction(Icon.createWithResource(c, icon), label, label, tap);
        shownGeneration = generation;
        ArrayList<RemoteAction> out = new ArrayList<>(stock);
        out.set(center, replacement);
        return out;
    }

    private static void refresh() {
        try {
            Activity activity = host.get();
            if (activity == null || activity.isDestroyed() || stock == null) return;
            boolean replay = target(activity) != null && Toggles.on(Toggles.PIP_REPLAY);
            if (replay && replacement != null && generation == shownGeneration || !replay && replacement == null) return;
            List<RemoteAction> actions = stock;
            if (replay) actions = withReplay(activity);
            else replacement = null;
            PipGuard.setParams(activity, new PictureInPictureParams.Builder().setActions(actions).build());
        } catch (Throwable t) {
            CrashReporter.report("RTPipReplay", "refresh", t);
        }
    }
}
