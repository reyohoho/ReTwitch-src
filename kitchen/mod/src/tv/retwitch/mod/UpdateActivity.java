package tv.retwitch.mod;

import android.app.Activity;
import android.os.Bundle;

/** A notification opens the same update controls as About, including when Twitch was closed. */
public final class UpdateActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SettingsScreen.show(this, ModUpdate.title(), ModUpdate::page, this::finish);
    }
}
