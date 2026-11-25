package tv.retwitch.mod;

/** Run with assertions enabled on the mod test classpath. */
public final class SettingsSearchSelfCheck {
    public static void main(String[] args) {
        String row = "cast";
        String scope = "скрыть элементы hide elements плеер player";
        assert SettingsScreen.matches(new String[]{"cast"}, row, scope);
        assert SettingsScreen.matches(new String[]{"скрыть", "cast"}, row, scope);
        assert SettingsScreen.matches(new String[]{"cast", "player", "hide"}, row, scope);
        assert !SettingsScreen.matches(new String[]{"чат", "cast"}, row, scope);
        assert !SettingsScreen.matches(new String[]{"player"}, row, scope);
    }
}
