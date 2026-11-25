package tv.retwitch.mod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.List;

import okhttp3.HttpUrl;

/** Run on the mod JVM self-check classpath with -ea and org.json before android.jar. */
public final class ClipRenditionSelfCheck {
    private static JSONObject rendition(String path, double fps) throws Exception {
        return new JSONObject().put("sourceURL", "https://cdn.example/" + path + "?a=1")
                .put("quality", "720").put("frameRate", fps);
    }

    private static JSONObject clip(String signature) throws Exception {
        return new JSONObject().put("playbackAccessToken", new JSONObject()
                        .put("value", new JSONObject().put("clip_slug", "ExampleClip").toString()).put("signature", signature))
                .put("videoQualities", new JSONArray().put(rendition("horizontal-60", 60)).put(rendition("horizontal-30", 30)))
                .put("assets", new JSONArray().put(new JSONObject().put("id", "portrait").put("videoQualities",
                        new JSONArray().put(rendition("vertical-30", 30)).put(rendition("vertical-60", 60)))));
    }

    public static void main(String[] args) throws Exception {
        List<VodDownload.Option> qualities = VodDownload.clipRenditions(clip("fresh"), "");
        assert VodDownload.clipPath(VodDownload.matchClip(qualities, "720", 60, "/obsolete")).equals("/horizontal-60");
        assert VodDownload.clipPath(VodDownload.matchClip(qualities, "720", 30, "/obsolete")).equals("/horizontal-30");
        try {
            VodDownload.matchClip(qualities, "720", 59.94, "");
            throw new AssertionError("must not silently substitute a different FPS");
        } catch (FileNotFoundException expected) {}
        try {
            VodDownload.matchClip(qualities, "720", 0, "");
            throw new AssertionError("several renditions fit this incomplete selection");
        } catch (FileNotFoundException expected) {}

        // A pre-upgrade job has only a signed URL. Its slug and path survive a new signature.
        String old = VodDownload.clipRenditions(clip("old"), "/vertical-30").get(1).url;
        String slug = VodDownload.clipSlugFromUrl(old), path = VodDownload.clipPath(old);
        assert "ExampleClip".equals(slug) && "/vertical-60".equals(path);
        String refreshed = VodDownload.matchClip(VodDownload.clipRenditions(clip("fresh"), path), "", 0, path);
        HttpUrl resolved = HttpUrl.get(refreshed);
        assert path.equals(resolved.encodedPath()) && "fresh".equals(resolved.queryParameter("sig"));
        assert "1".equals(resolved.queryParameter("a"));
        List<VodDownload.Option> portrait = VodDownload.clipRenditions(clip("new"), "/renamed", "portrait");
        assert VodDownload.clipPath(VodDownload.matchClip(portrait, "720", 30, "/renamed")).equals("/vertical-30");
        try {
            VodDownload.clipRenditions(clip("fresh"), "/missing", "deleted-asset");
            throw new AssertionError("missing portrait asset cannot become landscape");
        } catch (FileNotFoundException expected) {}
        assert VodDownload.clipSlugFromUrl("https://cdn.example/direct.mp4").isEmpty();
        assert VodDownload.clipSlugFromUrl("https://cdn.example/a?token=invalid").isEmpty();
        try {
            VodDownload.matchClip(qualities, "", 0, "/missing");
            throw new AssertionError("a legacy task cannot guess a replacement asset");
        } catch (FileNotFoundException expected) {}
        System.out.println("Clip renditions, FPS, guest asset path and legacy job refresh OK");
    }
}
