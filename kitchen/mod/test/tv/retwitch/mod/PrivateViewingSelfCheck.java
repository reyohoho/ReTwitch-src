package tv.retwitch.mod;

/** Run with assertions enabled on the mod test classpath. */
public final class PrivateViewingSelfCheck {
    public static void main(String[] args) {
        assert PrivateViewing.tokenOperation("StreamAccessTokenQuery");
        assert PrivateViewing.tokenOperation("VodAccessTokenQuery");
        assert !PrivateViewing.tokenOperation("FollowInSearchMutation");
        assert !PrivateViewing.tokenOperation("StreamAccessTokenQueryExtra");
        assert !PrivateViewing.tokenOperation(null);
        assert PrivateViewing.guestMarker("ReTwitch-Guest-Playback");
        assert !PrivateViewing.guestMarker("OAuth example");
        assert !PrivateViewing.guestMarker(null);
        assert PrivateViewing.EVENTS.contains("minute-watched");
        assert PrivateViewing.EVENTS.contains("clips_minute_watched");
        assert !PrivateViewing.EVENTS.contains("follow");
        assert !PrivateViewing.EVENTS.contains("purchase");
    }
}
