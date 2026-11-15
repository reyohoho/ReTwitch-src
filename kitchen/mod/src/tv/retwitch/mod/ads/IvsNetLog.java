package tv.retwitch.mod.ads;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tv.retwitch.mod.AppContext;

/**
 * For {@link VaftLog}: every HTTP request of the IVS player (segments straight from the CDN, which
 * {@link VaftServer} never sees, and its polls of our own server) and the app's default network. Hooked at
 * {@code com.amazonaws.ivs.net.HttpClientFactory.create()} (IVS SDK names aren't R8-renamed); a client
 * created while the log is off stays unwrapped, so nothing runs without the log. All by reflection and
 * {@link Proxy}: the IVS callbacks for the body are package-private interfaces.
 */
public final class IvsNetLog {

    private static final String TAG = "RTIvsNet";
    /** Requests waiting for headers or body, for the stall lines; dropped after a minute (cancelled ones never end). */
    private static final Map<Req, Boolean> PENDING = new ConcurrentHashMap<>();
    private static volatile boolean watching;
    /** The default network for the stats lines: transports, Wi-Fi signal, link estimate. */
    private static volatile String network = "?";

    private IvsNetLog() {
    }

    /** From {@code HttpClientFactory.create()}: IVS's client, or a logging proxy of it while the log is on. */
    public static Object wrap(Object client) {
        try {
            if (client == null || !VaftLog.isOn()) {
                return client;
            }
            watchNetwork();
            Class<?> api = Class.forName("com.amazonaws.ivs.net.HttpClient", false, client.getClass().getClassLoader());
            return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
                if ("execute".equals(method.getName()) && args != null && args.length == 2 && VaftLog.isOn()) {
                    try {
                        args[1] = track(args[0], args[1]);
                    } catch (Throwable t) {
                        Log.w(TAG, "track failed", t);
                    }
                }
                return call(client, method, args);
            });
        } catch (Throwable t) {
            Log.w(TAG, "wrap failed", t);
            return client;
        }
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class Req {
        final Object request;
        final String what;
        final boolean local;
        final long started = SystemClock.elapsedRealtime();
        volatile long headers;
        volatile int status;
        volatile long bytes;

        Req(Object request, String url) {
            this.request = request;
            local = url.contains("://127.0.0.1");
            what = local ? "local " + (url.contains("/master") ? "master" : url.contains("/media") ? "media" : "vod")
                    : VaftLog.describe(url);
        }
    }

    private static Object track(Object request, Object callback) throws Exception {
        Req req = new Req(request, String.valueOf(request.getClass().getMethod("getUrl").invoke(request)));
        PENDING.put(req, Boolean.TRUE);
        Class<?> api = Class.forName("com.amazonaws.ivs.net.ResponseCallback", false, callback.getClass().getClassLoader());
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            try {
                if ("onResponse".equals(method.getName()) && args != null && args.length == 1) {
                    onHeaders(req, args[0]);
                } else if ("onError".equals(method.getName()) && args != null && args.length == 1) {
                    fail(req, "", (Throwable) args[0]);
                }
            } catch (Throwable t) {
                Log.w(TAG, "callback log failed", t);
            }
            return call(callback, method, args);
        });
    }

    /** IVS's OkHttp callback sets the body consumer before handing the response on: wrap it to time the body. */
    private static void onHeaders(Req req, Object response) throws Exception {
        req.headers = SystemClock.elapsedRealtime();
        req.status = ((Number) response.getClass().getMethod("getStatus").invoke(response)).intValue();
        if (req.status != 200) {
            end(req, "");
            return;
        }
        Field field = response.getClass().getDeclaredField("consumer");
        field.setAccessible(true);
        Object consumer = field.get(response);
        if (consumer == null) {
            end(req, ", body not timed");
            return;
        }
        Class<?> consumerApi = field.getType();
        field.set(response, Proxy.newProxyInstance(consumerApi.getClassLoader(), new Class<?>[]{consumerApi},
                (proxy, method, args) -> {
                    if ("consume".equals(method.getName()) && args != null && args.length == 1 && args[0] != null) {
                        args[0] = readCallback(req, method.getParameterTypes()[0], args[0]);
                    }
                    return call(consumer, method, args);
                }));
    }

    private static Object readCallback(Req req, Class<?> api, Object target) {
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            boolean last = false;
            try {
                if ("onData".equals(method.getName()) && args != null && args.length >= 2) {
                    if (args[0] instanceof ByteBuffer) {
                        req.bytes += ((ByteBuffer) args[0]).remaining(); // before IVS drains it
                    } else if (args[1] instanceof Integer) {
                        req.bytes += (Integer) args[1];
                    }
                    last = Boolean.TRUE.equals(args[args.length - 1]);
                } else if ("onError".equals(method.getName()) && args != null && args.length == 1) {
                    fail(req, " in body", (Throwable) args[0]);
                }
            } catch (Throwable t) {
                Log.w(TAG, "body log failed", t);
            }
            Object result = call(target, method, args);
            if (last) {
                end(req, "");
            }
            return result;
        });
    }

    /** Segments always; our own server's polls only when slow or failed: VaftServer already logs each. */
    private static void end(Req req, String note) {
        if (PENDING.remove(req) == null) {
            return;
        }
        long total = SystemClock.elapsedRealtime() - req.started;
        if (req.local && req.status == 200 && total < 1000) {
            return;
        }
        String speed = req.bytes > 0 && total > 0
                ? String.format(Locale.US, " (%.1f Mbps)", req.bytes * 8.0 / total / 1000.0) : "";
        VaftLog.log("ivs " + req.what + " " + req.status + ": first byte " + (req.headers - req.started) + " ms, "
                + req.bytes / 1024 + " KB in " + total + " ms" + speed + note);
    }

    private static void fail(Req req, String where, Throwable error) {
        if (PENDING.remove(req) == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        VaftLog.log("ivs " + req.what + " failed" + where + " after " + (now - req.started) + " ms"
                + (req.headers > 0 ? " (first byte " + (req.headers - req.started) + " ms, " + req.bytes / 1024 + " KB)" : "")
                + ": " + error);
    }

    /**
     * For the player's stall and stats lines: every IVS request still open, oldest first, with its host. Only the oldest
     * was there before, and a tester's log showed a hung config request while the hung segments stayed unnamed.
     */
    public static String pending() {
        long now = SystemClock.elapsedRealtime();
        java.util.List<Req> open = new java.util.ArrayList<>();
        for (Req req : PENDING.keySet()) {
            if (now - req.started > 60_000 || cancelled(req.request)) { // a cancelled body just stops: no callback
                PENDING.remove(req);
            } else {
                open.add(req);
            }
        }
        java.util.Collections.sort(open, (a, b) -> Long.compare(a.started, b.started));
        StringBuilder out = new StringBuilder("ivs open ").append(open.size());
        for (int i = 0; i < open.size(); i++) {
            Req req = open.get(i);
            out.append(i == 0 ? ": " : "; ").append(now - req.started).append(" ms ")
                    .append(req.headers > 0 ? "in body " + req.bytes / 1024 + " KB" : "before headers").append(' ').append(req.what);
        }
        return out.toString();
    }

    private static boolean cancelled(Object request) {
        try {
            return Boolean.TRUE.equals(request.getClass().getMethod("isCancelled").invoke(request));
        } catch (Throwable t) {
            return false;
        }
    }

    public static String network() {
        return network;
    }

    /** The app's default network (with a split VPN, what Twitch actually uses): changes go to the log. */
    private static synchronized void watchNetwork() {
        Context context = AppContext.get();
        if (watching || context == null) {
            return;
        }
        watching = true;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            private String lastCaps;
            private String lastIface;

            @Override
            public void onAvailable(Network net) {
                VaftLog.log("net available: " + net);
            }

            @Override
            public void onLost(Network net) {
                network = "lost";
                VaftLog.log("net lost: " + net);
            }

            @Override
            public void onCapabilitiesChanged(Network net, NetworkCapabilities caps) {
                StringBuilder kind = new StringBuilder();
                append(kind, caps, NetworkCapabilities.TRANSPORT_WIFI, "wifi");
                append(kind, caps, NetworkCapabilities.TRANSPORT_CELLULAR, "cellular");
                append(kind, caps, NetworkCapabilities.TRANSPORT_ETHERNET, "ethernet");
                append(kind, caps, NetworkCapabilities.TRANSPORT_VPN, "vpn");
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    kind.append(" not-validated");
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    kind.append(" metered");
                }
                String signal = Build.VERSION.SDK_INT >= 29 && caps.getSignalStrength() != Integer.MIN_VALUE
                        ? ", signal " + caps.getSignalStrength() + (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? " dBm" : "")
                        : "";
                network = kind + signal + ", link " + caps.getLinkDownstreamBandwidthKbps() / 1000 + "/"
                        + caps.getLinkUpstreamBandwidthKbps() / 1000 + " Mbps";
                if (!kind.toString().equals(lastCaps)) { // signal and estimates move all the time: in the stats lines
                    lastCaps = kind.toString();
                    VaftLog.log("net " + net + ": " + network);
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network net, LinkProperties props) {
                String iface = props.getInterfaceName();
                if (iface != null && !iface.equals(lastIface)) {
                    lastIface = iface;
                    VaftLog.log("net " + net + " interface " + iface + ", dns " + props.getDnsServers().size()
                            + (props.getHttpProxy() != null ? ", http proxy" : ""));
                }
            }

            @Override
            public void onBlockedStatusChanged(Network net, boolean blocked) {
                VaftLog.log("net " + net + (blocked ? " blocked" : " unblocked"));
            }
        });
    }

    private static void append(StringBuilder out, NetworkCapabilities caps, int transport, String name) {
        if (caps.hasTransport(transport)) {
            out.append(out.length() > 0 ? "+" : "").append(name);
        }
    }
}
