package tv.retwitch.mod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The update check while the app is closed (UPDATE_NOTIFY, issue #11); ModUpdate.schedule sets its alarm. */
public final class UpdateCheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        if (!ModUpdate.backgroundChecks()) {
            ModUpdate.schedule(app);
            return;
        }
        final PendingResult result = goAsync();
        ModUpdate.backgroundCheck(app, result::finish);
    }
}
