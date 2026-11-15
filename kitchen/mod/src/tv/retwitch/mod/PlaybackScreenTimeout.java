package tv.retwitch.mod;

/** Holds the display during playback and gives stalled playback a finite grace period. */
final class PlaybackScreenTimeout {

    private static final long STALL_TIMEOUT_MS = 30_000L;

    private long lastProgressAt = -1;
    private long offlineSince = -1;
    private long lastPosition = -1;
    private long lastRenderedFrames = -1;
    private long lastTextureTimestamp = -1;
    private boolean positionMoves;
    private boolean renderedFramesMove;
    private boolean textureTimestampMoves;
    private boolean buffering;

    /** Pass elapsed realtime and -1 for either sample when it is unavailable. */
    boolean update(String state, long position, long renderedFrames, long now) {
        return update(state, position, renderedFrames, -1, false, now);
    }

    /** A texture timestamp measures video presentation. IVS statistics and position are fallbacks. */
    boolean update(String state, long position, long renderedFrames, long textureTimestamp, boolean offline, long now) {
        if ("IDLE".equals(state) || "READY".equals(state) || "ENDED".equals(state)) {
            reset();
            return false;
        }
        if (offline && offlineSince < 0) {
            offlineSince = now;
        } else if (!offline) {
            offlineSince = -1;
        }

        boolean playing = "PLAYING".equals(state);
        boolean positionChanged = playing && !offline && position >= 0 && lastPosition >= 0 && position != lastPosition;
        boolean framesAdvanced = lastRenderedFrames >= 0 && renderedFrames > lastRenderedFrames;
        // A new texture image can have an earlier timestamp after a backward seek.
        boolean textureAdvanced = textureTimestamp >= 0 && lastTextureTimestamp >= 0
                && textureTimestamp != lastTextureTimestamp;
        if (position >= 0) {
            lastPosition = position;
        }
        lastRenderedFrames = Math.max(lastRenderedFrames, renderedFrames);
        if (textureTimestamp >= 0) {
            lastTextureTimestamp = textureTimestamp;
        }
        positionMoves |= positionChanged;
        renderedFramesMove |= framesAdvanced;
        textureTimestampMoves |= textureAdvanced;

        boolean frameEvidence = renderedFramesMove || textureTimestampMoves;
        // A live clock can keep advancing while the last video frame remains on screen.
        boolean videoProgress = textureTimestampMoves ? textureAdvanced : framesAdvanced;
        boolean progress = videoProgress || (!frameEvidence && positionChanged);
        boolean unmeasuredPlayback = playing && !offline && !frameEvidence && !positionMoves;
        if (progress || unmeasuredPlayback || (lastProgressAt < 0 && (isActive(state) || offline))) {
            lastProgressAt = now;
        }
        if (progress || unmeasuredPlayback) {
            buffering = false;
        }
        buffering |= "BUFFERING".equals(state);

        if (offline) {
            return now - Math.max(offlineSince, lastProgressAt) < STALL_TIMEOUT_MS;
        }
        if (!isActive(state) && !frameEvidence && !buffering) {
            return true;
        }
        return lastProgressAt < 0 || now - lastProgressAt < STALL_TIMEOUT_MS;
    }

    static boolean isActive(String state) {
        return "PLAYING".equals(state) || "BUFFERING".equals(state);
    }

    /** A replacement player or texture needs a new baseline, but is still in the same theatre. */
    void rebind() {
        lastPosition = -1;
        lastRenderedFrames = -1;
        lastTextureTimestamp = -1;
    }

    /** A SurfaceView exposes frame statistics rather than a texture timestamp. */
    void useFrameCounter() {
        lastTextureTimestamp = -1;
        textureTimestampMoves = false;
    }

    void reset() {
        rebind();
        lastProgressAt = -1;
        offlineSince = -1;
        positionMoves = false;
        renderedFramesMove = false;
        textureTimestampMoves = false;
        buffering = false;
    }
}
