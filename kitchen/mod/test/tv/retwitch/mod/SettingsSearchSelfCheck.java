package tv.retwitch.mod;

/** Run with assertions enabled on the mod test classpath. */
public final class SettingsSearchSelfCheck {
    public static void main(String[] args) {
        StringBuilder displayed = new StringBuilder(), saved = new StringBuilder();
        android.widget.SeekBar.OnSeekBarChangeListener audio = SettingsScreen.sliderChanges(true, i -> displayed.append(i), i -> saved.append(i));
        audio.onStartTrackingTouch(null);
        audio.onProgressChanged(null, 3, true);
        audio.onProgressChanged(null, 5, true);
        assert displayed.toString().equals("35") && saved.length() == 0;
        audio.onStopTrackingTouch(null);
        audio.onProgressChanged(null, 6, true); // keyboard
        audio.onProgressChanged(null, 0, false); // a label edit has already saved its value
        assert saved.toString().equals("56");
        String row = "cast";
        String scope = "скрыть элементы hide elements плеер player";
        assert SettingsScreen.matches(new String[]{"cast"}, row, scope);
        assert SettingsScreen.matches(new String[]{"скрыть", "cast"}, row, scope);
        assert SettingsScreen.matches(new String[]{"cast", "player", "hide"}, row, scope);
        assert !SettingsScreen.matches(new String[]{"чат", "cast"}, row, scope);
        assert !SettingsScreen.matches(new String[]{"player"}, row, scope);
    }
}
