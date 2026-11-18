package tv.retwitch.mod;

import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hides sections of the React Native Following tab below the live channels and sorts its categories
 * ({@code RnFollowingSectionsPatch}). The tab is JS, so its GQL answers are emptied instead: every
 * RN {@code fetch} body passes the blob response handler as bytes. Categories and Continue Watching
 * are fields of {@code MwebFollowingTabSections}, offline channels the {@code follows} of
 * {@code MwebFollowedOfflineChannelsQuery}. An empty list is a normal state, the section then
 * renders nothing. Requests are batched, so a body may be an array of answers.
 */
public final class RnFollowingHook {

    private static final String TAG = "ReTwitch/RnFollowing";
    /** Twitch's own watch streak tag on Following cards, off behind its flag and experiment. */
    private static final String STREAK_FLAGS = "{\"ultralight_following_ws_tag\":true,\"ws-following-tab\":\"treatment\"}";
    private static final String SECTIONS_QUERY = "query MwebFollowingTabSections";
    private static final String CATEGORIES_FIELD = "followedGames {";
    private static final String VIEWED_FIELD = "viewedVideos(first: 1,";
    /** Viewed videos asked for while finished ones are skipped (#57): Continue watching shows one. */
    private static final int VIEWED_ASKED = 10;

    private RnFollowingHook() {
    }

    /**
     * The tab's query (JSON body, may be batched) sets no limit, and the server stops at the 10 oldest
     * follows. 100 is the most it accepts, more fails the field, and the field has no further pages.
     */
    static String withAllCategories(String body) {
        int query = body != null ? body.indexOf(SECTIONS_QUERY) : -1;
        int field = query >= 0 ? body.indexOf(CATEGORIES_FIELD, query) : -1;
        return field < 0 ? body : body.substring(0, field) + "followedGames(first: 100) {"
                + body.substring(field + CATEGORIES_FIELD.length());
    }

    /**
     * ResumeWatchingPatch (#57): the native Continue Watching list (31.9.0 {@code jsx}, the shelf under the VOD
     * player) without videos watched to the end. {@code history} maps each VodModel to its saved place, whose
     * only int field is the position in seconds.
     */
    public static List<?> resumeWatching(List<?> vods, java.util.Map<?, ?> history) {
        if (vods == null || history == null || vods.isEmpty() || !Toggles.on(Toggles.HIDE_FINISHED_CONTINUE)) {
            return vods;
        }
        try {
            List<Object> kept = new ArrayList<>(vods.size());
            for (Object vod : vods) {
                Object place = history.get(vod);
                int length = (Integer) vod.getClass().getMethod("getLength").invoke(vod);
                if (!finished(length, place == null ? -1 : intField(place))) {
                    kept.add(vod);
                }
            }
            Log.i(TAG, "resume watching: " + kept.size() + " of " + vods.size() + " kept");
            return kept.size() == vods.size() ? vods : kept;
        } catch (Throwable t) {
            Log.w(TAG, "resumeWatching failed", t);
            return vods;
        }
    }

    private static int intField(Object o) throws IllegalAccessException {
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return f.getInt(o);
            }
        }
        return -1;
    }

    /** Watched to the end: the saved place reached the length, not a second before. */
    static boolean finished(long length, long position) {
        return length > 0 && position >= length;
    }

    /** Continue watching asks for one video (#57): more, so one not finished is left after {@link #strip}. */
    static String withMoreViewed(String body) {
        int query = body != null ? body.indexOf(SECTIONS_QUERY) : -1;
        int field = query >= 0 ? body.indexOf(VIEWED_FIELD, query) : -1;
        return field < 0 ? body : body.substring(0, field) + "viewedVideos(first: " + VIEWED_ASKED + ","
                + body.substring(field + VIEWED_FIELD.length());
    }

    /**
     * Injected before {@code TwitchRNHostPropsBuilder.build} returns: JS reads flag overrides from the
     * {@code eppoFlagOverrides} prop, which the host never sets. Never throws.
     */
    public static void onHostProps(Bundle props) {
        try {
            if (props == null) {
                return;
            }
            JSONObject flags = new JSONObject(Toggles.on(Toggles.FOLLOWING_STREAK_TAG) ? STREAK_FLAGS : "{}");
            ExperimentHook.rnOverrides(flags);
            if (flags.length() > 0) {
                props.putString("eppoFlagOverrides", flags.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "onHostProps failed", t);
        }
    }

    /** Injected at the entry of the blob response handler: the body to keep. Never throws. */
    public static byte[] onResponse(byte[] body) {
        try {
            boolean categories = Toggles.on(Toggles.HIDE_FOLLOWING_CATEGORIES);
            boolean vods = Toggles.on(Toggles.HIDE_FOLLOWING_CONTINUE);
            boolean offline = Toggles.on(Toggles.HIDE_FOLLOWING_OFFLINE);
            boolean sort = Toggles.on(Toggles.SORT_FOLLOWED_CATEGORIES);
            boolean arrows = Toggles.on(Toggles.STREAM_ARROWS);
            boolean finished = Toggles.on(Toggles.HIDE_FINISHED_CONTINUE);
            if (body == null || !categories && !vods && !offline && !sort && !arrows && !finished) {
                return body;
            }
            String text = new String(body, StandardCharsets.UTF_8);
            String kept = strip(text, categories, vods, offline, sort, finished);
            return kept == text ? body : kept.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "onResponse failed", t);
            return body;
        }
    }

    /**
     * The answer with the hidden lists emptied and, with {@code sort}, the categories by name: the server
     * sends them oldest follow first. The same instance if there was nothing to change.
     */
    static String strip(String json, boolean categories, boolean vods, boolean offline, boolean sort)
            throws JSONException {
        return strip(json, categories, vods, offline, sort, false);
    }

    /** With {@code finished}: Continue watching keeps its first video not watched to the end (#57). */
    static String strip(String json, boolean categories, boolean vods, boolean offline, boolean sort, boolean finished)
            throws JSONException {
        if (!json.contains("\"followedLiveUsers\"") && !json.contains("\"followedVideos\"")) {
            return json;
        }
        Object root = new JSONTokener(json).nextValue();
        boolean changed = false;
        if (root instanceof JSONArray) {
            JSONArray batch = (JSONArray) root;
            for (int i = 0; i < batch.length(); i++) {
                changed |= stripOne(batch.optJSONObject(i), categories, vods, offline, sort, finished);
            }
        } else if (root instanceof JSONObject) {
            changed = stripOne((JSONObject) root, categories, vods, offline, sort, finished);
        }
        return changed ? root.toString() : json;
    }

    private static boolean stripOne(JSONObject answer, boolean categories, boolean vods, boolean offline,
            boolean sort, boolean finished) throws JSONException {
        JSONObject data = answer == null ? null : answer.optJSONObject("data");
        JSONObject user = data == null ? null : data.optJSONObject("user");
        if (user == null) {
            return false;
        }
        boolean changed = false;
        if (user.has("followedLiveUsers")) {
            StreamHistory.noteFollowing(user);
            changed |= categories ? empty(user.optJSONObject("followedGames"), "nodes")
                    : sort && sortByName(user.optJSONObject("followedGames"));
            changed |= vods ? empty(user.optJSONObject("viewedVideos"), "edges")
                    : finished && firstUnfinished(user.optJSONObject("viewedVideos"));
        } else if (offline) {
            // Only this query has currentUser.followedVideos next to follows: others need their follows.
            JSONObject me = data.optJSONObject("currentUser");
            JSONObject follows = user.optJSONObject("follows");
            if (me != null && me.has("followedVideos") && empty(follows, "edges")) {
                JSONObject page = follows.optJSONObject("pageInfo");
                if (page != null) {
                    page.put("hasNextPage", false); // the section walks all pages
                }
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Continue watching: the first edge whose saved place is before the video's end, alone (the query asked for
     * more, the section shows one); none left, the section is gone. Finished means the place reached the length.
     */
    private static boolean firstUnfinished(JSONObject viewed) throws JSONException {
        JSONArray edges = viewed == null ? null : viewed.optJSONArray("edges");
        if (edges == null) {
            return false;
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < edges.length() && kept.length() == 0; i++) {
            JSONObject edge = edges.optJSONObject(i);
            JSONObject node = edge == null ? null : edge.optJSONObject("node");
            JSONObject self = node == null ? null : node.optJSONObject("self");
            JSONObject history = self == null ? null : self.optJSONObject("viewingHistory");
            long length = node == null ? 0 : node.optLong("lengthSeconds", 0);
            long position = history == null ? -1 : history.optLong("position", -1);
            if (edge != null && !finished(length, position)) {
                kept.put(edge);
            }
        }
        if (kept.length() == edges.length() && edges.length() <= 1) {
            return false;
        }
        viewed.put("edges", kept);
        return true;
    }

    /** Categories by their shown name, in the device's language order. */
    private static boolean sortByName(JSONObject games) throws JSONException {
        JSONArray nodes = games == null ? null : games.optJSONArray("nodes");
        if (nodes == null || nodes.length() < 2) {
            return false;
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                return false; // not the shape we know: leave it
            }
            list.add(node);
        }
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        Collections.sort(list, (a, b) -> collator.compare(name(a), name(b)));
        games.put("nodes", new JSONArray(list));
        return true;
    }

    private static String name(JSONObject game) {
        String shown = game.optString("displayName", "");
        return shown.isEmpty() ? game.optString("name", "") : shown;
    }

    private static boolean empty(JSONObject holder, String list) throws JSONException {
        if (holder == null || !holder.has(list)) {
            return false;
        }
        holder.put(list, new JSONArray());
        return true;
    }
}
