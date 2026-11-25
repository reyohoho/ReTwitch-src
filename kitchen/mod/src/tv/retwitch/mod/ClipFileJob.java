package tv.retwitch.mod;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** DownloadManager only accepts file destinations. System jobs keep user-selected document downloads across restarts. */
public final class ClipFileJob extends JobService {
    private static final String TAG = "RTClipFile";
    // Serialize writes so a rescheduled job cannot truncate a document while its old worker still writes.
    private static final ExecutorService WORKERS = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashMap<Integer, Transfer> running = new HashMap<>();

    static void enqueue(Context c, Uri tree, String name, String slug, VodDownload.Option rendition) throws IOException {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        int id;
        do { id = UUID.randomUUID().hashCode() & Integer.MAX_VALUE; } while (scheduler.getPendingJob(id) != null);
        Uri file = DownloadFolder.create(c, tree, name, "video/mp4");
        try {
            PersistableBundle extras = new PersistableBundle();
            extras.putString("file", file.toString());
            if (slug != null && !slug.isEmpty()) {
                extras.putString("slug", slug);
                extras.putString("assetId", rendition.assetId);
                extras.putString("quality", rendition.quality);
                extras.putDouble("fps", rendition.fps);
                extras.putString("path", VodDownload.clipPath(rendition.url));
            } else {
                // Direct MP4 sources without a clip identity cannot be renewed.
                extras.putString("url", rendition.url);
            }
            extras.putString("name", name);
            JobInfo job = new JobInfo.Builder(id, new ComponentName(c, ClipFileJob.class))
                    .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IOException("Clip download not scheduled");
        } catch (IOException | RuntimeException e) {
            remove(c, file);
            throw e;
        }
    }

    /** JobScheduler can stop a job before it starts writing or while it is fetching a new playback token. */
    private static final class Transfer implements Call.Factory {
        private volatile boolean stopped;
        private volatile Call active;

        @Override public Call newCall(Request request) {
            Call call = VodDownload.HTTP.newCall(request);
            active = call;
            if (stopped) call.cancel();
            return call;
        }

        void stop() {
            stopped = true;
            Call call = active;
            if (call != null) call.cancel();
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        Transfer transfer = new Transfer();
        running.put(params.getJobId(), transfer);
        try {
            WORKERS.execute(() -> download(params, transfer));
            return true;
        } catch (RuntimeException e) {
            running.remove(params.getJobId());
            String file = params.getExtras().getString("file");
            if (file != null) remove(this, Uri.parse(file));
            Log.w(TAG, "clip job could not start", e);
            CrashReporter.report(TAG, "clip job could not start", e);
            Toast.makeText(this, Strings.downloadFailed(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void download(JobParameters params, Transfer transfer) {
        if (transfer.stopped) return;
        PersistableBundle extras = params.getExtras();
        String destination = extras.getString("file");
        Uri file = destination == null ? null : Uri.parse(destination);
        boolean saved = false;
        try {
            if (file == null) throw new IOException("Missing clip document");
            String previous = extras.getString("url");
            // Older pending jobs only contain the signed asset URL. Its token usually carries the clip slug;
            // match the original asset path against fresh renditions rather than guessing its quality.
            String slug = extras.getString("slug", VodDownload.clipSlugFromUrl(previous));
            String path = extras.getString("path", VodDownload.clipPath(previous));
            for (int attempt = 0; !transfer.stopped; attempt++) {
                try {
                    String url = previous;
                    if (!slug.isEmpty()) {
                        try {
                            url = VodDownload.freshClipUrl(transfer, slug, extras.getString("quality", ""),
                                    extras.getDouble("fps", 0), path, extras.getString("assetId", ""));
                        } catch (Exception e) {
                            if (previous == null || transfer.stopped) throw e;
                            // An old job with no matching metadata can still use its original URL while valid.
                        }
                    }
                    if (url == null) throw new FileNotFoundException("No clip source");
                    save(transfer, url, file);
                    saved = true;
                    break;
                } catch (FileNotFoundException | SecurityException terminal) {
                    throw terminal;
                } catch (IOException transientError) {
                    // Refresh once if the CDN refuses the signature or the connection breaks mid-file.
                    // A system stop is retried separately by JobScheduler with the same destination.
                    if (attempt != 0 || transfer.stopped) throw transientError;
                }
            }
        } catch (Throwable e) {
            if (!transfer.stopped) {
                Log.w(TAG, "clip download failed", e);
                CrashReporter.report(TAG, "clip download failed", e);
            }
        }
        final boolean complete = saved;
        main.post(() -> {
            if (running.get(params.getJobId()) != transfer) return;
            running.remove(params.getJobId());
            if (!complete && file != null) remove(this, file);
            Toast.makeText(this, complete ? Strings.pickf("Клип сохранён: %s", "Clip saved: %s", extras.getString("name"))
                    : Strings.downloadFailed(), Toast.LENGTH_LONG).show();
            jobFinished(params, false);
        });
    }

    private void save(Transfer transfer, String url, Uri file) throws IOException {
        try (Response response = transfer.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Clip HTTP " + response.code());
            if (transfer.stopped) throw new IOException("Clip job stopped");
            try (InputStream in = response.body().byteStream(); OutputStream out = getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) throw new FileNotFoundException("Cannot open clip document");
                java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();
                VodDownload.pipe(in, out, count);
                long expected = response.body().contentLength();
                if (count.get() == 0 || (expected >= 0 && count.get() != expected)) throw new IOException("Incomplete clip");
            }
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        Transfer transfer = running.remove(params.getJobId());
        if (transfer != null) transfer.stop();
        return true;
    }

    @Override public void onDestroy() {
        for (Transfer transfer : running.values()) transfer.stop();
        running.clear();
        super.onDestroy();
    }

    private static void remove(Context c, Uri file) {
        try {
            DocumentsContract.deleteDocument(c.getContentResolver(), file);
        } catch (Throwable e) {
            Log.w(TAG, "could not remove incomplete clip", e);
            CrashReporter.report(TAG, "could not remove incomplete clip", e);
        }
    }
}
