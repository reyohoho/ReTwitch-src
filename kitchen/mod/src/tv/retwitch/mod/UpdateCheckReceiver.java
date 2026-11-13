package tv.retwitch.mod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The update check while the app is closed (UPDATE_NOTIFY, issue #11); ModUpdate.schedule sets its alarm. */
public final class UpdateCheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        if (!Toggles.on(Toggles.UPDATE_NOTIFY)) {
            ModUpdate.schedule(app);
            return;
        }
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                ModUpdate.notifyIfNewer(app);
            } finally {
                result.finish();
            }
        }, "RTUpdateNotify").start();
    }
}
