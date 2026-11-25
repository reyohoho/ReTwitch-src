package tv.retwitch.mod;

/** The same choice for the lifetime of Twitch's in-memory recent searches and its disk observer. */
public final class BrowseSearchHistory {
    public static final boolean DISABLED = Toggles.on(Toggles.DISABLE_BROWSE_HISTORY);

    private BrowseSearchHistory() { }
}
