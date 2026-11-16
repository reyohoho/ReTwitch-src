package tv.retwitch.mod;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import tv.retwitch.mod.ads.VaftLog;

/** Controls display holds for the foreground native Twitch theatre, including its covered feed. */
public final class LiveScreenOn implements Runnable, View.OnAttachStateChangeListener {
    static final String THEATRE = "draggable_container";
    static final String PLAYER_AND_CHAT = "player_and_chat_vertical_container";
    private static final String TAG = "RTLiveScreenOn";
    private static final int KEY = 0x7e700d85;
    private static final int PLAYBACK_VIEW_KEY = 0x7e700d86;
    private static final long POLL_MS = 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TWITCH_VIEW = "tv.twitch.android.shared.player.core.TwitchPlayerView";
    private static final String IVS_PLAYER = "com.amazonaws.ivs.player.Player";
    private static final String IVS_MEDIA_PLAYER = "com.amazonaws.ivs.player.MediaPlayer";
    private static final String[] HOLDERS = {
            THEATRE, PLAYER_AND_CHAT, "discovery_feed_home_root", "discovery_feed_pager_container"
    };
    private static final Set<Object> PAUSED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private final Activity activity;
    private final View decor;
    private final ConnectivityManager connectivity;
    private final Set<View> suppressed = Collections.newSetFromMap(new WeakHashMap<>());
    private final Rect visible = new Rect();
    private final PlaybackScreenTimeout timeout = new PlaybackScreenTimeout();
    private Method getWrapper;
    private Method getWrapperState;
    private Class<?> wrapperType;
    private Field playerField;
    private Object currentWrapper;
    private Object player;
    private SurfaceTexture lastTexture;
    private Method getState;
    private Method getPosition;
    private Method getStatistics;
    private Method getRenderedFrames;
    private boolean observedActive;
    private boolean observedPlayback;
    private boolean idleSample;
    private boolean stopped;
    private boolean addedDecorHold;
    private boolean scheduled;
    private boolean unavailable;
    private String lastTrace;

    private LiveScreenOn(Activity activity, View decor) {
        this.activity = activity;
        this.decor = decor;
        connectivity = activity.getSystemService(ConnectivityManager.class);
        decor.addOnAttachStateChangeListener(this);
    }

    /** Called from the IVS pause/play methods after the native command. */
    public static void onPause(Object player) {
        PAUSED.add(player);
    }

    public static void onPlay(Object player) {
        PAUSED.remove(player);
    }

    /** The classic theatre attaches this rendering view directly, without a TwitchPlayerView. */
    public static void bindPlaybackView(Object wrapper, Object playbackView) {
        if (wrapper == null || playbackView == null) {
            return;
        }
        try {
            Method getter = playbackView.getClass().getMethod("getView");
            getter.setAccessible(true);
            Object view = getter.invoke(playbackView);
            if (view instanceof View) {
                WeakReference<Object> binding = new WeakReference<>(wrapper);
                Runnable bind = () -> ((View) view).setTag(PLAYBACK_VIEW_KEY, binding);
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    bind.run();
                } else {
                    MAIN.post(bind);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w(TAG, "Cannot bind the theatre playback view", failure);
        }
    }

    static void observe(Activity activity, View decor) {
        Object tag = decor.getTag(KEY);
        LiveScreenOn monitor = tag instanceof LiveScreenOn ? (LiveScreenOn) tag : null;
        if (monitor != null && monitor.scheduled) {
            return;
        }
        if (!hasVisibleRoot(UiHider.findAll(activity, decor, THEATRE))
                && !hasVisibleRoot(UiHider.findAll(activity, decor, PLAYER_AND_CHAT))) {
            return;
        }
        if (monitor == null) {
            monitor = new LiveScreenOn(activity, decor);
            decor.setTag(KEY, monitor);
        }
        monitor.start();
    }

    private static boolean hasVisibleRoot(List<View> views) {
        for (View view : views) {
            if (view.isShown() && view.getAlpha() > 0f) {
                return true;
            }
        }
        return false;
    }

    private void start() {
        if (!unavailable && !scheduled && decor.isAttachedToWindow() && decor.getWindowVisibility() == View.VISIBLE) {
            scheduled = MAIN.post(this);
        }
    }

    @Override
    public void run() {
        scheduled = false;
        if (!decor.isAttachedToWindow() || decor.getWindowVisibility() != View.VISIBLE) {
            clear();
            return;
        }
        try {
            if (update()) {
                scheduled = MAIN.postDelayed(this, POLL_MS);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable = true;
            clear();
            trace("unavailable");
            Log.w(TAG, "Cannot read the theatre player", failure);
        }
    }

    private boolean update() throws ReflectiveOperationException {
        List<View> theatres = UiHider.findAll(activity, decor, THEATRE);
        List<View> players = UiHider.findAll(activity, decor, PLAYER_AND_CHAT);
        if (!hasVisibleRoot(theatres) && !hasVisibleRoot(players)) {
            clear();
            return false;
        }
        Selection selection = new Selection();
        for (View theatre : theatres) {
            if (theatre.isShown()) {
                findPlayer(theatre, selection);
            }
        }
        for (View container : players) {
            if (container.isShown()) {
                findPlayer(container, selection);
            }
        }
        Object wrapper = selection.ambiguous ? null : selection.wrapper;
        Object current = unwrap(wrapper);
        boolean offline = networkOffline();
        boolean sameWrapper = wrapper != null && wrapper == currentWrapper;
        // Pause may release the SDK handle before the next poll.
        if (current == null && !selection.ambiguous && (wrapper == null || sameWrapper) && PAUSED.contains(player)) {
            stopped = true;
        }
        if (current != player || wrapper != currentWrapper) {
            currentWrapper = wrapper;
            player = current;
            lastTexture = null;
            observedActive = false;
            idleSample = false;
            timeout.rebind();
        }
        if (wrapper == null) {
            if (stopped && !selection.ambiguous) {
                applyHold(false);
                trace("stopped-unbound");
            } else {
                stopped = false;
                uncertain(selection.ambiguous ? "ambiguous player" : "unbound player", -1, -1, offline);
            }
            return true;
        }

        if (player != null && getState == null) {
            Class<?> ivs = Class.forName(IVS_PLAYER);
            getState = ivs.getMethod("getState");
            getPosition = ivs.getMethod("getPosition");
            getStatistics = optionalMethod(ivs, "getStatistics");
            if (getStatistics != null) {
                getRenderedFrames = optionalMethod(getStatistics.getReturnType(), "getRenderedFrames");
            }
        }
        String state = playbackState(wrapper);
        long timestamp = textureTimestamp(selection.view);
        if (state == null) {
            if (stopped && sameWrapper && player == null) {
                applyHold(false);
                trace("stopped-released");
                return true;
            }
            stopped = false;
            uncertain("unknown state", renderedFrames(), timestamp, offline);
            return true;
        }
        boolean active = PlaybackScreenTimeout.isActive(state);
        long position = active ? number(read(getPosition, player)) : -1L;
        long frames = active ? renderedFrames() : -1L;
        observedActive |= active;
        observedPlayback |= active;
        stopped = !active;
        boolean hold = timeout.update(state, position, frames, timestamp, offline, SystemClock.elapsedRealtime());
        applyHold(hold);
        trace(state + " hold=" + hold + " nativeBinding=" + selection.nativeBinding + " offline=" + offline
                + " texture=" + (timestamp >= 0) + " frames=" + (frames >= 0));
        return true;
    }

    private void uncertain(String reason, long frames, long timestamp, boolean offline) {
        if (!observedPlayback) {
            restore();
            trace(reason);
            return;
        }
        // Give a reconnecting player the remaining grace for this theatre.
        boolean hold = timeout.update(null, -1, frames, timestamp, offline, SystemClock.elapsedRealtime());
        applyHold(hold);
        trace(reason + " hold=" + hold + " offline=" + offline);
    }

    private long renderedFrames() throws IllegalAccessException {
        if (getRenderedFrames == null || player == null) {
            return -1;
        }
        return number(read(getRenderedFrames, read(getStatistics, player)));
    }

    private long textureTimestamp(View playbackView) {
        View renderer = findRenderingView(playbackView);
        if (renderer instanceof SurfaceView) {
            lastTexture = null;
            timeout.useFrameCounter();
            return -1;
        }
        SurfaceTexture texture = renderer instanceof TextureView ? ((TextureView) renderer).getSurfaceTexture() : null;
        if (texture == null) {
            return -1;
        }
        if (texture != lastTexture) {
            lastTexture = texture;
            timeout.rebind();
        }
        try {
            // The TextureView already acquires the image on Android's render thread.
            long timestamp = texture.getTimestamp();
            return timestamp > 0 ? timestamp : -1;
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private static View findRenderingView(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return null;
        }
        if (view instanceof SurfaceView || view instanceof TextureView) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View video = findRenderingView(group.getChildAt(i));
                if (video != null) {
                    return video;
                }
            }
        }
        return null;
    }

    private boolean networkOffline() {
        try {
            return connectivity != null && connectivity.getActiveNetwork() == null;
        } catch (SecurityException unavailable) {
            return false;
        }
    }

    private String playbackState(Object wrapper) throws IllegalAccessException {
        boolean priorIdle = idleSample;
        idleSample = false;
        String twitchState = enumName(read(getWrapperState, wrapper));
        if (PAUSED.contains(player) || "PAUSED".equals(twitchState)) {
            return "IDLE";
        }
        if ("PLAYBACK_COMPLETE".equals(twitchState)) {
            return "ENDED";
        }
        String sdkState = enumName(read(getState, player));
        if (PlaybackScreenTimeout.isActive(sdkState) || "ENDED".equals(sdkState)) {
            return sdkState;
        }
        if ("STOPPED".equals(twitchState) || "ERROR".equals(twitchState)) {
            return "IDLE";
        }
        if ("PLAYING".equals(twitchState)) {
            return "PLAYING";
        }
        if ("PREPARING".equals(twitchState)) {
            return "BUFFERING";
        }
        // An unused player or a transient restart can also report IDLE. Give it
        // another sample unless playback was already explicitly stopped.
        idleSample = observedActive && "IDLE".equals(sdkState) && "IDLE".equals(twitchState);
        return idleSample && (priorIdle || stopped) ? "IDLE" : null;
    }

    private static String enumName(Object state) {
        return state instanceof Enum<?> ? ((Enum<?>) state).name() : null;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static Method optionalMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException unavailable) {
            return null;
        }
    }

    private static Object read(Method method, Object player) throws IllegalAccessException {
        if (method == null || player == null) {
            return null;
        }
        try {
            return method.invoke(player);
        } catch (InvocationTargetException unavailable) {
            // The SDK can already have released this player while Twitch's view still refers to it.
            return null;
        }
    }

    private static final class Selection {
        Object wrapper;
        View view;
        long area;
        boolean ambiguous;
        boolean nativeBinding;
    }

    private void findPlayer(View root, Selection result) throws ReflectiveOperationException {
        if (root.getVisibility() != View.VISIBLE || root.getAlpha() <= 0f) {
            return;
        }
        Object tag = root.getTag(PLAYBACK_VIEW_KEY);
        Object bound = tag instanceof WeakReference<?> ? ((WeakReference<?>) tag).get() : null;
        boolean nativeBinding = bound != null;
        if (nativeBinding || TWITCH_VIEW.equals(root.getClass().getName())) {
            if (root.getGlobalVisibleRect(visible)) {
                long area = (long) visible.width() * visible.height();
                if (area > 0 && area >= result.area) {
                    if (!nativeBinding) {
                        if (getWrapper == null) {
                            getWrapper = root.getClass().getMethod("getPlayer");
                        }
                        bound = read(getWrapper, root);
                    }
                    if (bound != null && (area > result.area || (nativeBinding && !result.nativeBinding))) {
                        result.wrapper = bound;
                        result.view = root;
                        result.area = area;
                        result.ambiguous = false;
                        result.nativeBinding = nativeBinding;
                    } else if (bound != null && bound != result.wrapper && nativeBinding == result.nativeBinding) {
                        result.ambiguous = true;
                    }
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) root;
            for (int i = children.getChildCount() - 1; i >= 0; i--) {
                findPlayer(children.getChildAt(i), result);
            }
        }
    }

    private Object unwrap(Object wrapper) throws IllegalAccessException {
        if (wrapper == null) {
            return null;
        }
        if (wrapper.getClass() != wrapperType) {
            wrapperType = wrapper.getClass();
            playerField = sdkPlayerField(wrapperType);
            getWrapperState = optionalMethod(wrapperType, "getState");
            if (getWrapperState != null) {
                getWrapperState.setAccessible(true);
            }
        }
        return playerField == null ? null : playerField.get(wrapper);
    }

    private static Field sdkPlayerField(Class<?> type) {
        // The exact SDK field type survives Twitch's obfuscation. Its value is cleared when IVS is released.
        for (; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (IVS_MEDIA_PLAYER.equals(field.getType().getName())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    private void applyHold(boolean keep) {
        if (keep) {
            restoreHolders();
            if (!decor.getKeepScreenOn()) {
                decor.setKeepScreenOn(true);
                addedDecorHold = true;
            }
            return;
        }
        releaseDecorHold();
        for (String id : HOLDERS) {
            for (View view : UiHider.findAll(activity, decor, id)) {
                if (view.getKeepScreenOn()) {
                    suppressed.add(view);
                    view.setKeepScreenOn(false);
                }
            }
        }
    }

    private void releaseDecorHold() {
        if (addedDecorHold) {
            addedDecorHold = false;
            decor.setKeepScreenOn(false);
        }
    }

    private void restoreHolders() {
        for (View view : suppressed) {
            view.setKeepScreenOn(true);
        }
        suppressed.clear();
    }

    private void restore() {
        releaseDecorHold();
        restoreHolders();
    }

    private void trace(String decision) {
        if (!VaftLog.isOn()) {
            lastTrace = null;
            return;
        }
        Window window = activity.getWindow();
        boolean windowHold = window != null
                && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
        String line = "screen " + decision + " suppressed=" + suppressed.size() + " windowHold=" + windowHold;
        if (!line.equals(lastTrace)) {
            lastTrace = line;
            VaftLog.log(line);
        }
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        observe(activity, decor);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        MAIN.removeCallbacks(this);
        scheduled = false;
        clear();
    }

    private void clear() {
        currentWrapper = null;
        player = null;
        lastTexture = null;
        observedActive = false;
        observedPlayback = false;
        idleSample = false;
        stopped = false;
        timeout.reset();
        lastTrace = null;
        restore();
    }
}
