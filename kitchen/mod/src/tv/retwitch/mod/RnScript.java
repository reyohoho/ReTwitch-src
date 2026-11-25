package tv.retwitch.mod;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Evaluates {@code assets/retwitch-rn.js} ahead of Twitch's bundle. The script is only loaded while
 * something in it is on, so with the toggles off React Native runs untouched.
 */
public final class RnScript {

    private static final String TAG = "RnScript";
    private static final String ASSET = "retwitch-rn.js";
    /** Twitch's bundle has no segments, the id only names the script in native logs. */
    private static final int SEGMENT = 0x7ffffffe;

    private RnScript() {
    }

    /** The RN bundle exports separate light and dark themes. Supply both, including when Twitch overrides Android's mode. */
    private static JSONObject wallpaperColours() throws org.json.JSONException {
        if (android.os.Build.VERSION.SDK_INT < 31 || !Toggles.on(Toggles.SYSTEM_PALETTE)) return null;
        Context context = AppContext.get();
        if (context == null) return null;
        JSONObject colours = new JSONObject();
        for (String family : new String[]{"neutral1", "neutral2", "accent1"}) {
            for (int tone : new int[]{0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900}) {
                String name = family + "_" + tone;
                int id = context.getResources().getIdentifier("system_" + name, "color", "android");
                if (id == 0) return null;
                colours.put(name, String.format(Locale.ROOT, "#%06x", context.getColor(id) & 0xffffff));
            }
        }
        return colours;
    }

    /** Called at the start of {@code ReactInstance.loadJSBundle}. */
    public static void load(Object reactInstance) {
        try {
            JSONObject wallpaper = wallpaperColours();
            boolean compactLive = Toggles.on(Toggles.COMPACT_LIVE_TAB);
            boolean hideFollowedShelf = Toggles.on(Toggles.HIDE_LIVE_FOLLOWED_SHELF);
            boolean hideClipsShelf = Toggles.on(Toggles.HIDE_LIVE_CLIPS_SHELF);
            boolean hideLiveMute = Toggles.on(Toggles.HIDE_LIVE_MUTE);
            boolean hideLiveMore = Toggles.on(Toggles.HIDE_LIVE_MORE);
            boolean hideLiveFollow = Toggles.on(Toggles.HIDE_LIVE_FOLLOW);
            JSONObject clipButtons = new JSONObject();
            for (Toggles.Toggle button : Toggles.CLIP_BUTTONS) {
                if (Toggles.on(button)) clipButtons.put(button.key, true);
            }
            boolean hideStoriesEmpty = Toggles.on(Toggles.HIDE_STORIES_EMPTY);
            boolean hideLiveEmpty = Toggles.on(Toggles.HIDE_LIVE_EMPTY);
            boolean hideTags = Toggles.on(Toggles.HIDE_STREAM_TAGS);
            boolean hideCategory = Toggles.on(Toggles.HIDE_FOLLOWING_CATEGORY);
            boolean categoryTap = Toggles.on(Toggles.FOLLOWING_CATEGORY_TAP);
            boolean noHomeSwipe = Toggles.on(Toggles.NO_HOME_SWIPE);
            String me = TwitchAuth.getUserId();
            boolean friends = Toggles.on(Toggles.FRIENDS_HOME) && !me.isEmpty();
            int previewSize = LandingSettings.getFollowingPreviewSize();
            boolean tabIndicator = Toggles.on(Toggles.FEED_TAB_INDICATOR);
            boolean hideStories = Toggles.on(Toggles.HIDE_HOME_STORIES);
            boolean hideTabs = Toggles.on(Toggles.HIDE_FEED_TABS);
            boolean favorites = Toggles.on(Toggles.FAVORITE_CHANNELS);
            boolean liveToTop = Toggles.on(Toggles.LIVE_TAB_TO_TOP);
            boolean uptime = Toggles.on(Toggles.STREAM_UPTIME);
            boolean profileRecaps = Toggles.on(Toggles.HIDE_PROFILE_RECAPS);
            boolean profileClips = Toggles.on(Toggles.HIDE_PROFILE_FEATURED_CLIPS);
            boolean profileUserClips = Toggles.on(Toggles.HIDE_PROFILE_USER_CLIPS);
            boolean profileCategories = Toggles.on(Toggles.HIDE_PROFILE_CATEGORIES);
            boolean profileStreamers = Toggles.on(Toggles.HIDE_PROFILE_STREAMERS);
            if (!PrivateViewing.ENABLED && wallpaper == null && !compactLive && !hideFollowedShelf && !hideClipsShelf && !hideStoriesEmpty && !hideLiveEmpty && !hideTags && !friends
                    && !hideCategory && !categoryTap && !noHomeSwipe && !hideLiveMute && !hideLiveMore && !hideLiveFollow && clipButtons.length() == 0
                    && previewSize == 0
                    && !tabIndicator && !hideStories && !hideTabs && !liveToTop && !favorites && !uptime
                    && !profileRecaps && !profileClips && !profileUserClips && !profileCategories && !profileStreamers) {
                return;
            }
            JSONObject config = new JSONObject()
                    .put("wallpaper", wallpaper)
                    .put("privateClipEndpoint", PrivateViewing.ENABLED ? PrivateClipPlayback.endpoint() : null)
                    .put("privateViewEvents", PrivateViewing.ENABLED ? new org.json.JSONArray(PrivateViewing.EVENTS) : null)
                    .put("compactLive", compactLive)
                    .put("hideFollowedShelf", hideFollowedShelf)
                    .put("hideClipsShelf", hideClipsShelf)
                    .put("hideLiveMute", hideLiveMute)
                    .put("hideLiveMore", hideLiveMore)
                    .put("hideLiveFollow", hideLiveFollow)
                    .put("hiddenClipButtons", clipButtons.length() == 0 ? null : clipButtons)
                    .put("hideStoriesEmpty", hideStoriesEmpty)
                    .put("hideLiveEmpty", hideLiveEmpty)
                    .put("hideTags", hideTags)
                    .put("hideCategory", hideCategory)
                    .put("categoryTap", categoryTap)
                    .put("noHomeSwipe", noHomeSwipe)
                    .put("previewSize", previewSize)
                    .put("tabIndicator", tabIndicator)
                    .put("hideStories", hideStories)
                    .put("hideTabs", hideTabs)
                    .put("liveToTop", liveToTop)
                    .put("uptime", uptime)
                    .put("hideProfileRecaps", profileRecaps)
                    .put("hideProfileClips", profileClips)
                    .put("hideProfileUserClips", profileUserClips)
                    .put("hideProfileCategories", profileCategories)
                    .put("hideProfileStreamers", profileStreamers)
                    .put("friends", friends ? me : "")
                    .put("favorites", favorites ? FavoriteChannels.list() : null)
                    .put("favoritesCollapsed", favorites && FavoriteChannels.collapsed())
                    .put("text", new JSONObject()
                            .put("favorites", Strings.pick("Избранное", "Favorites"))
                            .put("favorite", Strings.pick("Добавить в избранное", "Add to favorites"))
                            .put("unfavorite", Strings.pick("Убрать из избранного", "Remove from favorites"))
                            .put("friends", Strings.pick("Друзья RTE", "RTE friends"))
                            .put("watching", Strings.pick("смотрит", "watching"))
                            .put("more", Strings.pick("Показать всех", "Show all"))
                            .put("less", Strings.pick("Свернуть", "Show less"))
                            // Stream uptime on previews: "2h 15m".
                            .put("hours", Strings.pick("ч", "h"))
                            .put("minutes", Strings.pick("м", "m")))
                    .put("locale", Locale.getDefault().toLanguageTag());
            Context context = AppContext.get();
            // registerSegment takes a path, and an asset has none.
            File file = new File(context.getCodeCacheDir(), ASSET);
            try (InputStream in = context.getAssets().open(ASSET); OutputStream out = new FileOutputStream(file)) {
                out.write(("globalThis.__retwitchRn=" + config + ";\n").getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
            reactInstance.getClass().getMethod("registerSegment", int.class, String.class)
                    .invoke(reactInstance, SEGMENT, file.getPath());
        } catch (Throwable t) {
            Log.w(TAG, "load failed", t);
            CrashReporter.report(TAG, "load failed", t);
        }
    }
}
