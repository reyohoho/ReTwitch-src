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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.view.View;
import android.widget.TextView;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final long LATER_MS = 24 * 60 * 60 * 1000L;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String FILE_NAME = "ReTwitch-update.apk";
    private static final int TIMEOUT_MS = 15000;
    private static final String KEY_GITHUB_FIRST = "github_first";
    /**
     * Yandex's S3 doesn't answer for some users: once a direct connection fails, the rest of the session goes through
     * the RTE emote proxy ({@link tv.retwitch.mod.emotes.EmoteProxy#proxied}, direct when that proxy is off).
     */
    private static volatile boolean viaProxy;
    private static final String GITHUB_RELEASES = "https://github.com/reyohoho/ReTwitch-src/releases/download/v";
    /** Accessed on the main thread. Every download uses the same staging file. */
    private static boolean downloading;

    private static final class Release {
        String version;
        String apk;
        String sha256;
        long size;
        String notes;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> top = new WeakReference<>(null);
    private static boolean checked;
    /** Found while no activity could show it. */
    private static Release waiting;
    /** Downloaded, waiting for the "install unknown apps" permission. */
    private static File ready;

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
            top = new WeakReference<>(activity);
            if (!checked) {
                checked = true;
                apkFile(activity).delete();
                schedule(activity);
                if ((Toggles.on(Toggles.UPDATE_CHECK) || Toggles.on(Toggles.UPDATE_NOTIFY)) && System.currentTimeMillis() >= prefs(activity).getLong(KEY_LATER, 0)) {
                    check(activity, false);
                }
            }
            if (ready != null && activity.getPackageManager().canRequestPackageInstalls()) {
                File file = ready;
                ready = null;
                openInstaller(activity, file);
            } else if (waiting != null) {
                Release release = waiting;
                waiting = null;
                offer(release);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resume failed", t);
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
            if (Toggles.on(Toggles.UPDATE_NOTIFY)) {
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

    /** From UpdateCheckReceiver, off the main thread: one notification per new version. */
    static void notifyIfNewer(Context app) {
        try {
            Release release = readRelease();
            SharedPreferences prefs = prefs(app);
            if (!BuildInfo.isNewer(release.version, BuildInfo.VERSION_NAME) || release.version.equals(prefs.getString(KEY_NOTIFIED, null))) {
                return;
            }
            NotificationManager notifications = app.getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                    Strings.pick("Обновления мода", "Mod updates"), NotificationManager.IMPORTANCE_DEFAULT));
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            Notification.Builder builder = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(AppContext.notificationIcon(app, android.R.drawable.stat_sys_download_done))
                    .setContentTitle(Strings.updateTitle(release.version))
                    .setContentText(Strings.pick("Откройте приложение, чтобы обновить", "Open the app to update"))
                    .setAutoCancel(true);
            if (launch != null) {
                builder.setContentIntent(PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE));
            }
            notifications.notify(NOTIFICATION_ID, builder.build());
            // The tap opens the app: the update dialog comes up then, "Later" from yesterday or not.
            prefs.edit().putString(KEY_NOTIFIED, release.version).remove(KEY_LATER).apply();
            // Not before the first resume: its own check offers the release, and this would make it a second dialog.
            MAIN.post(() -> {
                if (checked) {
                    waiting = release;
                }
            });
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "background check failed", e);
        }
    }

    /** From the settings: says what it found, even if there is nothing. */
    static void checkNow(Context context) {
        check(context, true);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void check(Context context, boolean manual) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Release release = readRelease();
                MAIN.post(() -> {
                    if (BuildInfo.isNewer(release.version, BuildInfo.VERSION_NAME)) {
                        offer(release);
                    } else if (manual) {
                        Toast.makeText(app, Strings.updateLatest(), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "check failed", e);
                if (manual) {
                    MAIN.post(() -> Toast.makeText(app, Strings.updateCheckFailed(), Toast.LENGTH_SHORT).show());
                }
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
        if (!release.apk.matches("[A-Za-z0-9._-]+\\.apk") || !release.sha256.matches("[0-9a-f]{64}") || release.size <= 0) {
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

    private static void offer(Release release) {
        Activity activity = top.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            waiting = release;
            return;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        Context themed = dialog.getContext();
        int pad = Math.round(20 * themed.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(themed);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, 0);
        TextView label = new TextView(themed);
        label.setText(Strings.pick("Сначала скачивать с", "Download first from"));
        content.addView(label);
        RadioGroup sources = new RadioGroup(themed);
        sources.setOrientation(themed.getResources().getConfiguration().fontScale > 1.3f
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        RadioButton yandex = new RadioButton(themed), github = new RadioButton(themed);
        yandex.setId(View.generateViewId());
        github.setId(View.generateViewId());
        yandex.setText(sourceLabel(UpdateApk.Source.YANDEX));
        github.setText(sourceLabel(UpdateApk.Source.GITHUB));
        sources.addView(yandex);
        sources.addView(github);
        sources.setId(View.generateViewId());
        label.setLabelFor(sources.getId());
        SharedPreferences settings = activity.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        sources.check(Boolean.TRUE.equals(settings.getAll().get(KEY_GITHUB_FIRST)) ? github.getId() : yandex.getId());
        sources.setOnCheckedChangeListener((group, id) -> settings.edit().putBoolean(KEY_GITHUB_FIRST, id == github.getId()).apply());
        content.addView(sources);
        TextView notes = new TextView(themed);
        notes.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        notes.setPadding(0, pad / 2, 0, 0);
        notes.setText(Html.fromHtml(release.notes, Html.FROM_HTML_MODE_LEGACY));
        content.addView(notes);
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(content);
        dialog.setTitle(Strings.updateTitle(release.version))
                .setView(scroll)
                .setPositiveButton(Strings.updateNow(), (d, which) -> download(activity, release,
                        github.isChecked() ? UpdateApk.Source.GITHUB : UpdateApk.Source.YANDEX))
                .setNegativeButton(Strings.later(), (d, which) -> prefs(activity).edit()
                        .putLong(KEY_LATER, System.currentTimeMillis() + LATER_MS).apply())
                .show();
    }

    private static File apkFile(Context context) {
        return new File(context.getExternalFilesDir("updates"), FILE_NAME);
    }

    private static String sourceLabel(UpdateApk.Source source) {
        return source == UpdateApk.Source.GITHUB ? "GitHub" : Strings.pick("Яндекс", "Yandex");
    }

    private static void download(Activity activity, Release release, UpdateApk.Source preferred) {
        if (downloading) return;
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView percent = new TextView(activity);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);
        TextView sourceName = new TextView(activity);
        sourceName.setText(sourceLabel(preferred));
        box.addView(sourceName);
        box.addView(bar);
        box.addView(percent);
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(Strings.updateDownloading(release.version))
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(Strings.cancel(), (d, which) -> cancelled.set(true))
                .show();
        final Context app = activity.getApplicationContext();
        final File file = apkFile(app);
        ready = null;
        downloading = true;
        new Thread(() -> {
            boolean ok = false;
            try {
                final long started = SystemClock.elapsedRealtime();
                ok = UpdateApk.fetch(file, release.size, release.sha256, preferred, cancelled,
                        (source, offset) -> source == UpdateApk.Source.GITHUB
                                ? connect(GITHUB_RELEASES + release.version + "/" + release.apk, offset)
                                : open(BASE_URL + release.apk, offset),
                        (source, done) -> {
                    long elapsed = SystemClock.elapsedRealtime() - started;
                    // Time left once two seconds of data give a speed.
                    long left = elapsed > 2000 && done > 0 ? (release.size - done) * elapsed / done / 1000 : -1;
                    MAIN.post(() -> {
                        sourceName.setText(sourceLabel(source));
                        bar.setProgress((int) (done * 100 / release.size));
                        percent.setText(Strings.updateProgress(done, release.size, left));
                    });
                });
            } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
                Log.w(TAG, "download failed", e);
                if (!cancelled.get()) {
                    CrashReporter.report(TAG, "download failed", e);
                }
            }
            final boolean done = ok;
            MAIN.post(() -> {
                try {
                    dialog.dismiss();
                } catch (RuntimeException e) {
                    Log.w(TAG, "dismiss failed", e); // the activity went away
                }
                downloading = false;
                if (done && !cancelled.get()) {
                    finish(app, file);
                } else {
                    file.delete();
                    if (!cancelled.get()) {
                        Toast.makeText(app, Strings.updateFailed(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }, "RTUpdateDownload").start();
    }

    private static void finish(Context app, File file) {
        Activity activity = top.get();
        if (activity == null) {
            return;
        }
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            openInstaller(activity, file);
            return;
        }
        ready = file; // resumed() goes on once the permission screen closes
        Toast.makeText(app, Strings.updateAllowInstall(), Toast.LENGTH_LONG).show();
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
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
                    .setDataAndType(sharedUri(activity, file), APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "installer failed", e);
            CrashReporter.report(TAG, "installer failed", e);
            Toast.makeText(activity, Strings.updateFailed(), Toast.LENGTH_LONG).show();
        }
    }
}
