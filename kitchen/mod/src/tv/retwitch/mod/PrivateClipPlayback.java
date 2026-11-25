package tv.retwitch.mod;

import android.net.Uri;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import tv.retwitch.mod.ads.VaftServer;

/** Guest media for clips. Feed results stay authenticated; only the clip slug and unsigned asset path go to loopback. */
public final class PrivateClipPlayback {
    private static final String UNAVAILABLE = "http://127.0.0.1:0/clip.mp4";
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9_-]{1,256}");
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static volatile String local;
    private static final LruCache<String, HttpUrl> cache = new LruCache<>(12);

    private PrivateClipPlayback() {}

    static String endpoint() {
        if (local == null) {
            try {
                local = VaftServer.clipEndpoint();
            } catch (Exception e) {
                return UNAVAILABLE;
            }
        }
        return local;
    }

    public static String manifestCredential(String original) {
        return PrivateViewing.ENABLED ? "" : original;
    }

    /** Twitch passes the feed token here only to read its public clip slug. Its credentials never enter a player URL. */
    public static String localUrl(String source, String feedToken) {
        String base = endpoint();
        try {
            String slug = new JSONObject(feedToken).getString("clip_slug");
            String asset = VodDownload.clipPath(source);
            if (SLUG.matcher(slug).matches() && !asset.isEmpty()) {
                return Uri.parse(base).buildUpon().appendQueryParameter("slug", slug)
                        .appendQueryParameter("asset", asset).toString();
            }
        } catch (Exception ignored) {
            // A clip without a slug cannot be requested as a guest.
        }
        return base;
    }

    private static Uri ownUrl(String value) {
        if (local == null || value == null || !value.startsWith(local + "?")) return null;
        return Uri.parse(value);
    }

    public static String slugFromUrl(String value) {
        Uri uri = ownUrl(value);
        String slug = uri == null ? null : uri.getQueryParameter("slug");
        return slug != null && SLUG.matcher(slug).matches() ? slug : null;
    }

    public static String assetPathFromUrl(String value) {
        Uri uri = ownUrl(value);
        return uri == null ? null : uri.getQueryParameter("asset");
    }

    private static boolean mediaHost(HttpUrl url) {
        if (url == null || !url.isHttps() || url.port() != 443 || !url.username().isEmpty() || !url.password().isEmpty()) return false;
        String h = url.host();
        return h.endsWith(".clips.twitchcdn.net") || h.equals("d1ndex63qxojbr.cloudfront.net")
                || h.equals("clips-media-assets.twitch.tv") || h.equals("clips-media-assets2.twitch.tv");
    }

    private static boolean guestToken(HttpUrl url, String slug, long minExpiry) {
        try {
            JSONObject token = new JSONObject(url.queryParameter("token"));
            String user = token.isNull("user_id") ? "" : token.getString("user_id");
            return (user.isEmpty() || "0".equals(user)) && token.has("user_id") &&
                    slug.equals(token.getString("clip_slug")) && !token.getJSONObject("authorization").getBoolean("forbidden") &&
                    url.queryParameter("sig") != null && token.getLong("expires") > minExpiry;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static HttpUrl guestUrl(String slug, String asset) throws Exception {
        String key = slug + "\n" + asset;
        long now = System.currentTimeMillis() / 1000;
        HttpUrl found = cache.get(key);
        if (found != null && guestToken(found, slug, now + 15)) return found;
        HttpUrl fresh = HttpUrl.parse(VodDownload.freshClipUrl(VodDownload.HTTP, slug, "", 0, asset));
        if (!mediaHost(fresh) || !guestToken(fresh, slug, now)) throw new IOException("Guest clip unavailable");
        cache.put(key, fresh);
        return fresh;
    }

    private static final class MediaClient {
        static final OkHttpClient HTTP = VodDownload.SEGMENTS.newBuilder().followRedirects(false).followSslRedirects(false).build();
    }

    /** GET/HEAD and byte ranges stay on the same origin for IVS. Never forward cookies, account headers or signed input URLs. */
    public static void serve(Socket socket, Uri request, String range, boolean head) throws IOException {
        OutputStream out = socket.getOutputStream();
        boolean committed = false;
        try {
            String slug = request.getQueryParameter("slug"), asset = request.getQueryParameter("asset");
            if (!PrivateViewing.ENABLED || slug == null || !SLUG.matcher(slug).matches() || asset == null ||
                    !asset.startsWith("/") || asset.length() > 4096) throw new IOException();
            HttpUrl cdn = guestUrl(slug, asset);
            for (int redirects = 0; redirects < 4; redirects++) {
                Request.Builder get = new Request.Builder().url(cdn);
                if (head) get.head();
                if (range != null) get.header("Range", range);
                Call call = MediaClient.HTTP.newCall(get.build());
                try (Response response = call.execute()) {
                    if (response.isRedirect()) {
                        String location = response.header("Location");
                        cdn = location == null ? null : cdn.resolve(location);
                        if (!mediaHost(cdn)) throw new IOException();
                        continue;
                    }
                    int code = response.code();
                    if (code == 401 || code == 403) cache.remove(slug + "\n" + asset);
                    StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(code).append(" Media\r\n");
                    for (String key : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"}) {
                        String value = response.header(key);
                        if (value != null) headers.append(key).append(": ").append(value).append("\r\n");
                    }
                    headers.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
                    committed = true;
                    out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
                    if (!REPORTED.get() && response.isSuccessful() && DebugServer.enabled() && REPORTED.compareAndSet(false, true)) {
                        Log.i("RTPrivateClip", "Guest clip media confirmed");
                    }
                    if (!head && response.body() != null) {
                        InputStream bytes = response.body().byteStream();
                        byte[] chunk = new byte[16 * 1024];
                        int n;
                        while ((n = bytes.read(chunk)) != -1) out.write(chunk, 0, n);
                    }
                    return;
                } finally {
                    call.cancel();
                }
            }
        } catch (Exception ignored) {
            // A disconnected player also arrives here. Upstream error messages can include a signed media URL.
        }
        if (!committed) out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }
}
