package tv.retwitch.mod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One setting per process so native and React Native players agree for the whole playback session. */
public final class PrivateViewing {
    static final Set<String> EVENTS = new HashSet<>(Arrays.asList(
            "video-play", "video_play_anon", "video_init", "video_end", "video_pause", "video_resume",
            "video_seek_success", "video_loop", "minute-watched", "clips_minute_watched", "n_second_play",
            "first_video_engagement", "video_engagement", "costreaming_video_play"));
    public static final boolean ENABLED = Toggles.on(Toggles.PRIVATE_VIEWING);

    private PrivateViewing() {}

    public static boolean tokenOperation(String name) {
        return "StreamAccessTokenQuery".equals(name) || "VodAccessTokenQuery".equals(name);
    }

    public static boolean guestMarker(String header) {
        return "ReTwitch-Guest-Playback".equals(header);
    }

    public static boolean skipEvent(String name) {
        return ENABLED && EVENTS.contains(name);
    }
}
