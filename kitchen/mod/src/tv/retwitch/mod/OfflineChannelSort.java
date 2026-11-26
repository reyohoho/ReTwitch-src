package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;

import tv.retwitch.mod.ads.VaftServer;

/** Sort preference and explicitly enabled, account-scoped live playback time for the RN offline shelf. */
public final class OfflineChannelSort {
    static final String WATCH_TIME = "retwitch_offline_watch_time";
    private static final String TAG = "RTOfflineSort";
    private static final long CHECKPOINT_MS = 30_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Object> player = new WeakReference<>(null);
    private static WeakReference<Object> candidate = new WeakReference<>(null);
    private static String candidateLogin;
    private static String key;
    private static long started = -1;

    private OfflineChannelSort() {}

    // A settings import from another phone must not silently opt this device into tracking.
    private static int mode() {
        Context c = AppContext.get();
        return c == null ? 0 : c.getSharedPreferences(WATCH_TIME, Context.MODE_PRIVATE).getInt("mode", 0);
    }

    private static boolean byWatchTime(int mode) {
        return mode == 1 || mode == 3;
    }

    private static boolean recording() {
        return !PrivateViewing.ENABLED && Toggles.on(Toggles.OFFLINE_SORT) && byWatchTime(mode());
    }

    /** StreamSwitch identifies the live (not seekable) theatre including proxied streams. Main thread. */
    static void liveChannel(boolean liveTheatre, String login) {
        if (!liveTheatre || !PlayerHook.theatreOpen() || !recording()) {
            if (key != null) stop();
            return;
        }
        Object active = PlayerHook.currentPlayer();
        Object observed = candidate.get();
        if (observed != null && candidateLogin.equals(ChannelTracker.getCurrentChannelLogin())) {
            // currentPlayer() tracks audible sources. A muted IVS source also counts when its own URL
            // identifies the channel open in the live theatre; an unrelated silent feed preview does not.
            active = observed;
            login = candidateLogin;
        }
        if (active == null || login == null) { stop(); return; }
        String normalized = login.toLowerCase(Locale.ROOT);
        if (player.get() == active && key != null && key.endsWith("/" + normalized)) return;
        stop();
        String user = TwitchAuth.getUserId();
        if (user.isEmpty()) return;
        key = user + "/" + normalized;
        player = new WeakReference<>(active);
        try {
            state(active, active.getClass().getMethod("getState").invoke(active).toString());
        } catch (Exception e) {
            stop();
            CrashReporter.report(TAG, "read player state", e);
        }
    }

    /** Includes pauses and buffering even if a previously audible player was just muted. */
    static void state(Object source, String state) {
        if ("PLAYING".equals(state) && source != player.get() && recording()) {
            String live = LiveRecord.playerLogin(source);
            if (live != null) {
                candidate = new WeakReference<>(source);
                candidateLogin = live;
            }
        }
        if (key == null || player.get() != source) return;
        if (!"PLAYING".equals(state) || !recording()) {
            flush();
        } else if (started < 0) {
            started = SystemClock.uptimeMillis();
            MAIN.postDelayed(CHECKPOINT, CHECKPOINT_MS);
        }
    }

    static void stop() {
        flush();
        key = null;
        player.clear();
    }

    private static void flush() {
        MAIN.removeCallbacks(CHECKPOINT);
        if (started < 0) return;
        long ms = SystemClock.uptimeMillis() - started;
        started = -1;
        Context c = AppContext.get();
        if (ms > 0 && c != null && key != null && recording() && key.startsWith(TwitchAuth.getUserId() + "/")) {
            SharedPreferences sp = c.getSharedPreferences(WATCH_TIME, Context.MODE_PRIVATE);
            sp.edit().putLong(key, sp.getLong(key, 0) + ms).apply();
        }
    }

    private static final Runnable CHECKPOINT = new Runnable() {
        @Override public void run() {
            try {
                Object p = player.get();
                if (p == null || !recording() || !PlayerHook.theatreOpen() || (p != PlayerHook.currentPlayer()
                        && (p != candidate.get() || !candidateLogin.equals(ChannelTracker.getCurrentChannelLogin())))) {
                    stop();
                    return;
                }
                flush();
                state(p, p.getClass().getMethod("getState").invoke(p).toString());
            } catch (Throwable t) {
                stop();
                Log.w(TAG, "watch time failed", t);
                CrashReporter.report(TAG, "watch time failed", t);
            }
        }
    };

    static JSONObject config() throws Exception {
        boolean showMenu = Toggles.on(Toggles.OFFLINE_SORT);
        int mode = mode();
        if (!showMenu && mode == 0) return null;
        String url;
        try {
            url = VaftServer.offlineSortEndpoint();
        } catch (java.io.IOException e) {
            CrashReporter.report(TAG, "offline menu unavailable", e);
            return null;
        }
        return new JSONObject().put("url", url)
                .put("mode", mode).put("showMenu", showMenu)
                .put("title", Strings.pick("Сортировка неактивных каналов", "Sort offline channels"))
                .put("stock", Strings.pick("Как в Twitch", "As in Twitch"))
                .put("frequency", Strings.pick("Чаще смотрю на этом устройстве", "Most watched on this device"))
                .put("least", Strings.pick("Реже смотрю на этом устройстве", "Least watched on this device"))
                .put("alpha", Strings.pick("По алфавиту", "A to Z"))
                .put("reverseAlpha", Strings.pick("В обратном алфавитном порядке", "Z to A"));
    }

    /** Called only inside the secret loopback route, GET reads, POST chooses a mode. */
    public static String response(Uri uri, boolean change) throws JSONException {
        Context c = AppContext.get();
        if (c == null) return "{}";
        if (change && Toggles.on(Toggles.OFFLINE_SORT)) {
            String mode = uri.getQueryParameter("mode");
            if (mode != null && mode.length() == 1 && mode.charAt(0) >= '0' && mode.charAt(0) <= '4') {
                int next = Integer.parseInt(mode);
                c.getSharedPreferences(WATCH_TIME, Context.MODE_PRIVATE).edit().putInt("mode", next).apply();
                if (!byWatchTime(next)) MAIN.post(OfflineChannelSort::stop);
            }
        }
        int mode = mode();
        JSONObject ranks = new JSONObject();
        String user = TwitchAuth.getUserId();
        if (byWatchTime(mode) && !user.isEmpty()) {
            String prefix = user + "/";
            for (Map.Entry<String, ?> e : c.getSharedPreferences(WATCH_TIME, Context.MODE_PRIVATE).getAll().entrySet()) {
                if (e.getKey().startsWith(prefix) && e.getValue() instanceof Long) {
                    ranks.put(e.getKey().substring(prefix.length()), e.getValue());
                }
            }
        }
        return new JSONObject().put("mode", mode).put("ranks", ranks).toString();
    }
}
