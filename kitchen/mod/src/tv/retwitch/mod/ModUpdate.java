package tv.retwitch.mod;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Html;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mod updates from the public {@code latest.json} published next to the APK.
 * The file's SHA-256 comes from that JSON, and Android itself refuses an APK signed by another key.
 * The system installer always asks: an app can't update itself silently.
 */
final class ModUpdate {

    private static final String TAG = "RTUpdate";
    private static final String BASE_URL = "https://storage.yandexcloud.net/rte/ReTwitch/";
    private static final String PREFS = "retwitch_update";
    private static final String KEY_LATER = "later_until";
    private static final String KEY_NOTIFIED = "notified_version";
    /** The background check's interval (issue #22): a setting, so outside the backup-excluded state file above. */
    private static final String SETTINGS = "retwitch_update_settings";
    private static final String KEY_INTERVAL = "interval_minutes";
    static final int[] INTERVAL_MINUTES = {30, 60, 180, 360, 720, 1440};
    private static final int DEFAULT_MINUTES = 720;
    /**
     * Before 30 minutes came in (issue #24) the choice was saved as an index into these hours: read when the minutes
     * aren't there yet, so a saved "every hour" doesn't slide to the option now in its place.
     */
    private static final String KEY_INTERVAL_OLD = "interval_index";
    private static final int[] OLD_INTERVAL_HOURS = {1, 3, 6, 12, 24};
    private static final String CHANNEL = "retwitch_update";
    private static final int NOTIFICATION_ID = 0x7e7001;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final int TIMEOUT_MS = 15000;
    private static final String KEY_GITHUB_FIRST = "github_first";
    /**
     * Yandex's S3 doesn't answer for some users: once a direct connection fails, the rest of the session goes through
     * the RTE emote proxy ({@link tv.retwitch.mod.emotes.EmoteProxy#proxied}, direct when that proxy is off).
     */
    private static volatile boolean viaProxy;
    private static final String GITHUB_RELEASES = "https://github.com/reyohoho/ReTwitch-src/releases/download/v";
    private static final String TRANSFER = "download_request";
    private static final String FAILED = "download_failed";
    private static final String SKIPPED = "download_skipped";
    private static WeakReference<SettingsScreen> screen = new WeakReference<>(null);
    private static boolean checking;

    static final class Release {
        String version;
        String apk;
        String sha256;
        long size;
        String notes;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean checked;
    /** Only an explicit Install tap may continue after the system permission dialog. */
    private static String permissionFor;
    private static WeakReference<Activity> permissionHost = new WeakReference<>(null);

    private ModUpdate() {
    }

    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityResumed(Activity a) { resumed(a); }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    /** First resume, not Application.onCreate: that one runs in Twitch's background processes too. */
    private static void resumed(Activity activity) {
        try {
            if (!checked) {
                checked = true;
                SharedPreferences state = prefs(activity);
                String version = state.getString("version", "");
                if (!version.isEmpty() && !BuildInfo.isNewer(version, BuildInfo.VERSION_NAME)) {
                    discard(activity);
                    state.edit().clear().apply();
                } else if (pending(activity) && !UpdateDownloadJob.scheduled(activity)) {
                    // Android may discard a scheduled job on a force stop.
                    queue(activity, stored(activity), state.getBoolean("unmetered", false));
                }
                schedule(activity);
                if ((Toggles.on(Toggles.UPDATE_CHECK) || Toggles.on(Toggles.UPDATE_NOTIFY) || Toggles.on(Toggles.UPDATE_AUTO_DOWNLOAD))
                        && System.currentTimeMillis() >= state.getLong(KEY_LATER, 0)) checkNow(activity, false);
            }
            if (permissionFor != null && permissionHost.get() == activity) {
                String hash = permissionFor;
                permissionFor = null;
                Release r = stored(activity);
                if (r != null && hash.equals(r.sha256) && ready(activity, r) && activity.getPackageManager().canRequestPackageInstalls()) {
                    openInstaller(activity, file(activity, r, false));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resume failed", t);
            CrashReporter.report(TAG, "resume failed", t);
        }
    }

    /**
     * The background check (UPDATE_NOTIFY) at the chosen interval, inexact and without waking the phone. The alarm lives
     * until a reboot or a force stop; every launch sets it again, and the receiver cancels it once the toggle is off.
     */
    static void schedule(Context context) {
        try {
            Context app = context.getApplicationContext();
            AlarmManager alarms = app.getSystemService(AlarmManager.class);
            PendingIntent check = PendingIntent.getBroadcast(app, 0, new Intent(app, UpdateCheckReceiver.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            if (backgroundChecks()) {
                long every = INTERVAL_MINUTES[intervalIndex(app)] * 60_000L;
                alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + every, every, check);
            } else {
                alarms.cancel(check);
            }
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed", t);
        }
    }

    /** Position of the saved interval in {@link #INTERVAL_MINUTES}. */
    static int intervalIndex(Context context) {
        SharedPreferences settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        int minutes = settings.getInt(KEY_INTERVAL, -1);
        if (minutes < 0) {
            int old = settings.getInt(KEY_INTERVAL_OLD, -1);
            minutes = old >= 0 && old < OLD_INTERVAL_HOURS.length ? OLD_INTERVAL_HOURS[old] * 60 : DEFAULT_MINUTES;
        }
        int fallback = 0;
        for (int i = 0; i < INTERVAL_MINUTES.length; i++) {
            if (INTERVAL_MINUTES[i] == minutes) {
                return i;
            }
            if (INTERVAL_MINUTES[i] == DEFAULT_MINUTES) {
                fallback = i;
            }
        }
        return fallback;
    }

    /** From the setting: saved, and the alarm set again at once. */
    static void setIntervalIndex(Context context, int index) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_INTERVAL, INTERVAL_MINUTES[index]).remove(KEY_INTERVAL_OLD).apply();
        schedule(context);
    }

    /** Keep the receiver alive until the manifest result has been recorded and its job scheduled. */
    static void backgroundCheck(Context app, Runnable finished) {
        new Thread(() -> {
            try {
                Release r = readRelease();
                MAIN.post(() -> {
                    try {
                        if (backgroundChecks()) found(app, r, false);
                    } finally {
                        finished.run();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "background check failed", e);
                CrashReporter.report(TAG, "background check failed", e);
                finished.run();
            }
        }, "RTUpdateNotify").start();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean backgroundChecks() {
        return Toggles.on(Toggles.UPDATE_NOTIFY) || Toggles.on(Toggles.UPDATE_AUTO_DOWNLOAD);
    }

    private static void checkNow(Context context, boolean manual) {
        if (checking) return;
        checking = true;
        refresh();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Release r = readRelease();
                MAIN.post(() -> {
                    checking = false;
                    found(app, r, manual);
                });
            } catch (Exception e) {
                Log.w(TAG, "check failed", e);
                CrashReporter.report(TAG, "check failed", e);
                MAIN.post(() -> {
                    checking = false;
                    if (manual) Toast.makeText(app, Strings.updateCheckFailed(), Toast.LENGTH_SHORT).show();
                    refresh();
                });
            }
        }, "RTUpdateCheck").start();
    }

    private static Release readRelease() throws IOException, JSONException {
        try {
            return parse(read(open(BASE_URL + "latest.json", 0)));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Update manifest unavailable, checking the GitHub release", e);
            return parseGithub(read(connect("https://api.github.com/repos/reyohoho/ReTwitch-src/releases/latest", 0)));
        }
    }

    private static String read(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    /** {@code url} directly or, after a direct failure, through the proxy ({@link #viaProxy}). */
    private static HttpURLConnection open(String url, long from) throws IOException {
        if (!viaProxy) {
            try {
                return connect(url, from);
            } catch (IOException e) {
                Log.w(TAG, "direct failed, going through the proxy", e);
                viaProxy = true;
            }
        }
        String proxied = tv.retwitch.mod.emotes.EmoteProxy.proxied(url);
        try {
            return connect(proxied, from);
        } catch (IOException e) {
            tv.retwitch.mod.emotes.EmoteProxy.failed(proxied);
            throw e;
        }
    }

    private static HttpURLConnection connect(String url, long from) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        if (from > 0) {
            connection.setRequestProperty("Range", "bytes=" + from + "-");
        }
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && (from == 0 || code != HttpURLConnection.HTTP_PARTIAL)) {
                throw new IOException("HTTP " + code);
            }
            return connection;
        } catch (IOException e) {
            connection.disconnect();
            throw e;
        }
    }

    /** The JSON comes from the network: only a plain file name, and no update without a hash. */
    private static Release parse(String body) throws JSONException, IOException {
        JSONObject json = new JSONObject(body);
        Release release = new Release();
        release.version = json.getString("version");
        release.apk = json.getString("apk");
        release.sha256 = json.getString("sha256");
        release.size = json.getLong("size");
        if (!release.version.matches("[0-9]+(?:\\.[0-9]+){2}(?:-[a-zA-Z0-9.-]+)?") || !release.apk.matches("[A-Za-z0-9._-]+\\.apk") || !release.sha256.matches("[0-9a-f]{64}") || release.size <= 0) {
            throw new IOException("malformed latest.json");
        }
        StringBuilder notes = new StringBuilder();
        JSONArray history = json.optJSONArray("history");
        for (int i = 0; history != null && i < history.length(); i++) {
            JSONObject entry = history.getJSONObject(i);
            String version = entry.getString("version");
            if (BuildInfo.isNewer(version, BuildInfo.VERSION_NAME)) {
                notes.append(notes.length() == 0 ? "" : "<br><br>");
                Changelog.entry(notes, version, Strings.pick(entry.optString("ru"), entry.optString("en")), false);
            }
        }
        release.notes = notes.toString();
        return release;
    }

    /** Older releases have no latest.json asset on GitHub. Use the published APK asset's digest and size. */
    private static Release parseGithub(String body) throws JSONException, IOException {
        JSONObject json = new JSONObject(body);
        String tag = json.getString("tag_name");
        if (!tag.matches("v[0-9]+(?:\\.[0-9]+){2}(?:-[a-zA-Z0-9.-]+)?") || json.getBoolean("draft")
                || json.getBoolean("prerelease")) throw new IOException("Invalid GitHub release");
        String version = tag.substring(1);
        String name = "ReTwitch_" + version + ".apk";
        JSONArray assets = json.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!name.equals(asset.optString("name")) || !"uploaded".equals(asset.optString("state"))) continue;
            String digest = asset.optString("digest");
            if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IOException("Missing GitHub APK checksum");
            String notes = json.optString("body");
            String en = githubNotes(notes, "English"), ru = githubNotes(notes, "Русский");
            JSONObject entry = new JSONObject().put("version", version).put("en", en).put("ru", ru.isEmpty() ? en : ru);
            return parse(new JSONObject().put("version", version).put("apk", name).put("sha256", digest.substring(7))
                    .put("size", asset.getLong("size")).put("history", new JSONArray().put(entry)).toString());
        }
        throw new IOException("GitHub release has no matching APK");
    }

    private static String githubNotes(String notes, String language) {
        StringBuilder bullets = new StringBuilder();
        boolean inSection = false;
        for (String line : notes.split("\n")) {
            line = line.trim();
            if (line.startsWith("## ") || "EN:".equals(line) || "RU:".equals(line)) {
                inSection = line.equals("## " + language) || line.equals(language.equals("English") ? "EN:" : "RU:");
            } else if (inSection && line.startsWith("- ")) {
                if (bullets.length() > 0) bullets.append('\n');
                bullets.append(line.substring(2));
            }
        }
        return bullets.toString();
    }

    private static Release stored(Context c) {
        SharedPreferences p = prefs(c);
        String version = p.getString("version", "");
        if (!BuildInfo.isNewer(version, BuildInfo.VERSION_NAME)) return null;
        Release r = new Release();
        r.version = version;
        r.sha256 = p.getString("hash", "");
        r.size = p.getLong("size", 0);
        r.apk = p.getString("apk", "");
        r.notes = p.getString("notes", "");
        return r;
    }

    static File file(Context c, Release r, boolean partial) {
        return new File(new File(c.getCacheDir(), "retwitch-updates"), r.sha256 + (partial ? ".part" : ".apk"));
    }

    static boolean ready(Context c, Release r) {
        return file(c, r, false).length() == r.size && r.size > 0;
    }

    static boolean pending(Context c) {
        return prefs(c).contains(TRANSFER);
    }

    static Release requested(Context c, String request) {
        return request != null && request.equals(prefs(c).getString(TRANSFER, null)) ? stored(c) : null;
    }

    private static void discard(Context c) {
        UpdateDownloadJob.cancel(c);
        String hash = prefs(c).getString("hash", "");
        if (!hash.isEmpty()) {
            Release old = new Release();
            old.sha256 = hash;
            file(c, old, true).delete();
            file(c, old, false).delete();
        }
        c.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        permissionFor = null;
    }

    private static void found(Context c, Release r, boolean manual) {
        try {
            acceptRelease(c, r, manual);
        } catch (Throwable t) {
            Log.w(TAG, "update check result failed", t);
            CrashReporter.report(TAG, "update check result failed", t);
            if (manual) Toast.makeText(c, Strings.updateCheckFailed(), Toast.LENGTH_SHORT).show();
        } finally {
            refresh();
        }
    }

    private static void acceptRelease(Context c, Release r, boolean manual) {
        if (!BuildInfo.isNewer(r.version, BuildInfo.VERSION_NAME)) {
            if (manual) Toast.makeText(c, Strings.updateLatest(), Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences p = prefs(c);
        Release old = stored(c);
        if (old != null && BuildInfo.isNewer(old.version, r.version)) return;
        if (old == null || !r.sha256.equals(old.sha256)) {
            discard(c);
            p.edit().remove(TRANSFER).remove(FAILED).remove(SKIPPED).remove(KEY_NOTIFIED)
                    .putString("version", r.version).putString("hash", r.sha256).putLong("size", r.size)
                    .putString("apk", r.apk).putString("notes", r.notes).apply();
        }
        if (Toggles.on(Toggles.UPDATE_AUTO_DOWNLOAD)) {
            if (!ready(c, r) && !pending(c) && !p.getBoolean(SKIPPED, false) && !p.getBoolean(FAILED, false)) queue(c, r, true);
        } else if (!manual && !ready(c, r) && !pending(c) && !p.getBoolean(SKIPPED, false)) {
            notify(c, r, false);
        }
    }

    private static void notify(Context c, Release r, boolean ready) {
        String key = r.sha256 + (ready ? ":ready" : ":available");
        if (key.equals(prefs(c).getString(KEY_NOTIFIED, null))) return;
        try {
            NotificationManager manager = c.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, title(), NotificationManager.IMPORTANCE_DEFAULT));
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            PendingIntent tap = PendingIntent.getActivity(c, NOTIFICATION_ID, new Intent(c, UpdateActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(NOTIFICATION_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(AppContext.notificationIcon(c, android.R.drawable.stat_sys_download_done))
                    .setContentTitle(Strings.updateTitle(r.version)).setContentText(ready ? readyText()
                            : Strings.pick("Доступна новая версия", "A new version is available"))
                    .setContentIntent(tap).setAutoCancel(true).build());
            prefs(c).edit().putString(KEY_NOTIFIED, key).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "notification failed", e);
            CrashReporter.report(TAG, "notification failed", e);
        }
    }

    private static void queue(Context c, Release r, boolean unmetered) {
        if (r == null || ready(c, r)) return;
        String id = java.util.UUID.randomUUID().toString();
        SharedPreferences p = prefs(c);
        p.edit().putString(TRANSFER, id).remove("retries").putBoolean("unmetered", unmetered).remove(FAILED).remove(SKIPPED).apply();
        try {
            UpdateDownloadJob.enqueue(c, id, unmetered);
            c.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        } catch (RuntimeException e) {
            Log.w(TAG, "queue failed", e);
            CrashReporter.report(TAG, "queue failed", e);
            p.edit().remove(TRANSFER).putBoolean(FAILED, true).apply();
        }
        refresh();
    }

    /** A short background outage should not need a tap. Keep the partial file between bounded system retries. */
    static boolean retryAutomatically(Context c, String id) {
        if (requested(c, id) == null || !prefs(c).getBoolean("unmetered", false)) return false;
        int tries = prefs(c).getInt("retries", 0);
        if (tries >= 2) return false;
        prefs(c).edit().putInt("retries", tries + 1).apply();
        refresh();
        return true;
    }

    static void finished(Context c, String id, boolean verified) {
        Release r = requested(c, id);
        if (r == null) return;
        boolean ok = ready(c, r) || verified && file(c, r, true).renameTo(file(c, r, false));
        prefs(c).edit().remove(TRANSFER).putBoolean(FAILED, !ok).apply();
        if (ok) notify(c, r, true);
        refresh();
    }

    private static void cancel(Context c, Release r) {
        prefs(c).edit().remove(TRANSFER).putBoolean(SKIPPED, true).remove(FAILED).apply();
        UpdateDownloadJob.cancel(c);
        file(c, r, true).delete();
        refresh();
    }

    static void autoChanged(Context c) {
        schedule(c);
        Release r = stored(c);
        if (Toggles.on(Toggles.UPDATE_AUTO_DOWNLOAD)) {
            if (r != null && !pending(c)) queue(c, r, true);
            if (r == null) checkNow(c, false);
        } else if (r != null && pending(c) && prefs(c).getBoolean("unmetered", false)) {
            cancel(c, r);
        }
    }

    static HttpURLConnection mirror(Release r, UpdateApk.Source source, long offset) throws IOException {
        return source == UpdateApk.Source.GITHUB ? connect(GITHUB_RELEASES + r.version + "/" + r.apk, offset)
                : open(BASE_URL + r.apk, offset);
    }

    static UpdateApk.Source preferred(Context c) {
        return c.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_GITHUB_FIRST, false)
                ? UpdateApk.Source.GITHUB : UpdateApk.Source.YANDEX;
    }

    static String sourceLabel(UpdateApk.Source source) {
        return source == UpdateApk.Source.GITHUB ? "GitHub" : Strings.pick("Яндекс", "Yandex");
    }

    static String title() { return Strings.pick("Обновления мода", "Mod updates"); }
    private static String readyText() { return Strings.pick("Готово к установке", "Ready to install"); }

    static String homeSubtitle(Context c, String fallback) {
        return stored(c) == null ? fallback : summary(c);
    }

    static String summary(Context c) {
        Release r = stored(c);
        if (r == null) return Strings.pickf("Установлена версия %s", "Installed version %s", BuildInfo.VERSION_NAME);
        return Strings.updateTitle(r.version) + " · " + (ready(c, r) ? readyText() : pending(c)
                ? Strings.pick("Загрузка", "Downloading") : Strings.pick("Доступно", "Available"));
    }

    static void refresh() {
        SettingsScreen s = screen.get();
        if (s != null) s.refreshIfVisible();
    }

    static void page(SettingsScreen s) {
        Context c = s.context;
        if (!s.searching()) screen = new WeakReference<>(s);
        Release r = stored(c);
        s.section(summary(c));
        if (r != null) {
            if (ready(c, r)) {
                s.action(Strings.pick("Установить обновление", "Install update"), readyText(), () -> installReady(c, r));
            } else if (pending(c)) {
                boolean unmetered = prefs(c).getBoolean("unmetered", false);
                s.progress(Strings.updateTitle(r.version), () -> UpdateDownloadJob.status(unmetered),
                        () -> (int) (100 * file(c, r, true).length() / r.size), () -> "", null, () -> cancel(c, r));
                if (unmetered && !UpdateDownloadJob.active()) {
                    s.action(Strings.pick("Скачать через любую сеть", "Download on any network"), null, () -> queue(c, r, false));
                }
            } else {
                s.action(prefs(c).getBoolean(FAILED, false) ? Strings.pick("Повторить загрузку", "Retry download")
                                : Strings.pick("Скачать обновление", "Download update"),
                        android.text.format.Formatter.formatFileSize(c, r.size), () -> queue(c, r, false));
            }
            if (!r.notes.isEmpty()) s.action(Strings.changelogTitle(), r.version, () -> new AlertDialog.Builder(c)
                    .setTitle(Strings.updateTitle(r.version)).setMessage(Html.fromHtml(r.notes, Html.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(android.R.string.ok, null).show());
        }
        s.action(checking ? Strings.pick("Проверяем обновления...", "Checking for updates...")
                : Strings.pick("Проверить обновления сейчас", "Check for updates now"), null, () -> checkNow(c, true));
        s.section(Strings.pick("Автоматические обновления", "Automatic updates"));
        s.toggle(Toggles.UPDATE_CHECK);
        s.toggle(Toggles.UPDATE_NOTIFY);
        s.toggle(Toggles.UPDATE_AUTO_DOWNLOAD);
        NotificationManager notifications = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        boolean appNotifications = notifications.areNotificationsEnabled();
        if (!appNotifications || channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            s.action(Strings.pick("Разрешить уведомления Android", "Allow Android notifications"),
                    Strings.pick("Сейчас уведомления выключены. Готовое обновление можно установить здесь.",
                            "Notifications are off. You can install a downloaded update here."), () -> {
                        Intent intent = new Intent(appNotifications ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                        if (appNotifications) intent.putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
                        c.startActivity(intent);
                    });
        }
        if (backgroundChecks() || s.searching()) {
            s.markNew("0.5.32-beta").choice(Strings.pick("Как часто проверять", "How often to check"),
                    new String[]{Strings.pick("Раз в 30 минут", "Every 30 minutes"), Strings.pick("Раз в час", "Every hour"),
                            Strings.pick("Раз в 3 часа", "Every 3 hours"), Strings.pick("Раз в 6 часов", "Every 6 hours"),
                            Strings.pick("Дважды в день", "Twice a day"), Strings.pick("Раз в день", "Once a day")},
                    intervalIndex(c), false, i -> setIntervalIndex(c, i));
        }
        s.choice(Strings.pick("Сначала скачивать с", "Download first from"),
                new String[]{sourceLabel(UpdateApk.Source.YANDEX), "GitHub"}, preferred(c) == UpdateApk.Source.GITHUB ? 1 : 0, false,
                i -> c.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GITHUB_FIRST, i == 1).apply());
    }

    private static void installReady(Context c, Release r) {
        Activity a = SettingsBackup.activity(c);
        if (a == null || !ready(c, r)) { refresh(); return; }
        if (a.getPackageManager().canRequestPackageInstalls()) openInstaller(a, file(c, r, false));
        else {
            permissionHost = new WeakReference<>(a);
            permissionFor = r.sha256;
            Toast.makeText(a, Strings.updateAllowInstall(), Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
        }
    }

    /** Twitch's FileProvider exports external storage as "shared"; its class is obfuscated, so the URI is built here. */
    static Uri sharedUri(Context context, File file) throws IOException {
        String root = Environment.getExternalStorageDirectory().getCanonicalPath();
        Uri.Builder uri = new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".fileprovider").appendPath("shared");
        for (String part : file.getCanonicalPath().substring(root.length() + 1).split("/")) {
            uri.appendPath(part);
        }
        return uri.build();
    }

    private static void openInstaller(Activity activity, File file) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".provider")
                            .appendPath("debug").appendPath("retwitch-updates").appendPath(file.getName()).build(), APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException e) {
            Log.w(TAG, "installer failed", e);
            CrashReporter.report(TAG, "installer failed", e);
            Toast.makeText(activity, Strings.updateFailed(), Toast.LENGTH_LONG).show();
        }
    }
}
