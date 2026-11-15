package tv.retwitch.mod;

/**
 * JVM self-check for the screen hold policy. Run from kitchen/:
 *   javac -d work/selfcheck mod/src/tv/retwitch/mod/PlaybackScreenTimeout.java mod/test/tv/retwitch/mod/PlaybackScreenTimeoutSelfCheck.java
 *   java -ea -cp work/selfcheck tv.retwitch.mod.PlaybackScreenTimeoutSelfCheck
 */
public final class PlaybackScreenTimeoutSelfCheck {

    public static void main(String[] args) {
        PlaybackScreenTimeout screen = new PlaybackScreenTimeout();
        assert !screen.update("READY", 0, -1, 0);
        assert screen.update("BUFFERING", -1, -1, 10);
        assert screen.update("PLAYING", 0, -1, 1_000);
        assert screen.update("PLAYING", 25_000, -1, 26_000);
        assert screen.update("PLAYING", 50_000, -1, 51_000);
        assert !screen.update("IDLE", 50_000, -1, 51_010); // IVS pause
        assert screen.update("PLAYING", 50_000, -1, 51_020);
        assert !screen.update("ENDED", 50_000, -1, 51_030);

        assert screen.update("PLAYING", 0, -1, 59_000);
        assert screen.update("PLAYING", 100, -1, 60_000);
        assert screen.update("BUFFERING", 100, -1, 70_000);
        assert screen.update("PLAYING", 100, -1, 89_999);
        assert !screen.update("BUFFERING", 100, -1, 90_000);
        assert !screen.update("PLAYING", 100, -1, 91_000); // no progress despite "PLAYING"
        assert screen.update("PLAYING", 500, -1, 92_000); // recovered
        assert screen.update("PLAYING", 0, -1, 110_000); // backwards seek
        assert !screen.update("PLAYING", 0, -1, 140_000); // frozen in steady PLAYING

        screen.reset(); // switching or leaving the player
        assert screen.update(null, -1, -1, 159_000); // no previous playback evidence
        assert screen.update("BUFFERING", -1, -1, 160_000);
        assert !screen.update("BUFFERING", -1, -1, 190_000);
        assert !screen.update(null, -1, -1, 191_000); // unreadable state does not restart buffering

        // A flat live timeline does not hide real frame progress.
        screen.reset();
        assert screen.update("PLAYING", 0, 9, 200_000);
        assert screen.update("PLAYING", 0, 10, 229_000);
        assert screen.update("PLAYING", 0, 11, 258_000);
        assert !screen.update("PLAYING", 0, 11, 288_000);
        assert screen.update("PLAYING", 0, 12, 290_000);
        assert !screen.update("PLAYING", 0, -1, 330_000); // losing a calibrated reading does not restart the timer
        assert !screen.update("ENDED", 0, -1, 331_000);

        // An initially static or missing counter cannot prove a stall.
        assert screen.update("PLAYING", 0, 0, 340_000);
        assert screen.update("PLAYING", 0, 0, 390_000);
        assert screen.update("PLAYING", -1, -1, 440_000);
        assert screen.update("BUFFERING", -1, -1, 441_000);
        assert !screen.update("BUFFERING", -1, -1, 470_000);

        // A progressing live clock does not keep a known frozen renderer awake.
        screen.reset();
        assert screen.update("PLAYING", 0, 0, 0);
        assert screen.update("PLAYING", 1_000, 30, 1_000);
        assert screen.update("PLAYING", 10_000, -1, 10_000);
        assert screen.update("BUFFERING", -1, 0, 20_000);
        assert !screen.update("PLAYING", 31_000, 30, 31_000);
        assert !screen.update(null, -1, -1, 32_000);
        assert screen.update("PLAYING", 33_000, 31, 33_000);

        // The display timestamp is stronger evidence than a changing SDK frame counter.
        screen.reset();
        assert screen.update("PLAYING", 0, 0, 0, false, 0);
        assert screen.update("PLAYING", 1_000, 30, 100, false, 1_000);
        assert screen.update("PLAYING", 20_000, 600, -1, false, 20_000);
        assert !screen.update("PLAYING", 31_000, 900, 100, false, 31_000);
        assert screen.update("PLAYING", 32_000, 930, 20, false, 32_000); // first frame after a backward seek
        assert screen.update("PLAYING", 33_000, 960, 21, false, 33_000);
        assert screen.update("PLAYING", 34_000, 990, 22, false, 34_000);

        // Decoder retries use new baselines while the same theatre's deadline continues.
        screen.rebind();
        assert screen.update("BUFFERING", -1, 0, 0, false, 50_000);
        screen.rebind();
        assert !screen.update("PLAYING", 64_000, 0, 0, false, 64_000);
        assert !screen.update(null, -1, -1, -1, false, 65_000);
        assert screen.update("PLAYING", 66_000, 1, 1, false, 66_000);

        // Offline grace includes frames still playing from the buffer, but never the SDK clock alone.
        screen.reset();
        assert screen.update("PLAYING", 0, -1, -1, false, 0);
        assert screen.update("PLAYING", 1_000, -1, -1, true, 1_000);
        assert screen.update("PLAYING", 30_999, -1, -1, true, 30_999);
        assert !screen.update("PLAYING", 31_000, -1, -1, true, 31_000);
        assert !screen.update(null, -1, -1, -1, true, 32_000);
        assert !screen.update("BUFFERING", -1, 12, -1, true, 33_000); // first reading is a baseline
        assert screen.update("PLAYING", 34_000, 13, -1, true, 34_000);
        assert screen.update("PLAYING", 63_999, 13, -1, true, 63_999);
        assert !screen.update("PLAYING", 64_000, 13, -1, true, 64_000);
        System.out.println("PlaybackScreenTimeoutSelfCheck OK");
    }
}
