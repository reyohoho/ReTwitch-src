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
    private final HashMap<Integer, Call> running = new HashMap<>();

    static void enqueue(Context c, Uri tree, String name, String url) throws IOException {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        int id;
        do { id = UUID.randomUUID().hashCode() & Integer.MAX_VALUE; } while (scheduler.getPendingJob(id) != null);
        Uri file = DownloadFolder.create(c, tree, name, "video/mp4");
        try {
            PersistableBundle extras = new PersistableBundle();
            extras.putString("file", file.toString());
            extras.putString("url", url);
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

    @Override public boolean onStartJob(JobParameters params) {
        try {
            int id = params.getJobId();
            Call call = VodDownload.HTTP.newCall(new Request.Builder().url(params.getExtras().getString("url")).build());
            running.put(id, call);
            WORKERS.execute(() -> download(params, call));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "clip job could not start", t);
            CrashReporter.report(TAG, "clip job could not start", t);
            Toast.makeText(this, Strings.downloadFailed(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void download(JobParameters params, Call call) {
        if (call.isCanceled()) return;
        Uri file = Uri.parse(params.getExtras().getString("file"));
        boolean saved = false;
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            try (InputStream in = response.body().byteStream(); OutputStream out = getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) throw new IOException("Cannot open clip document");
                VodDownload.pipe(in, out, null);
            }
            saved = true;
        } catch (Throwable e) {
            if (!call.isCanceled()) {
                Log.w(TAG, "clip download failed", e);
                CrashReporter.report(TAG, "clip download failed", e);
            }
        }
        final boolean complete = saved;
        main.post(() -> {
            if (running.get(params.getJobId()) != call) return; // Android will retry a stopped job.
            running.remove(params.getJobId());
            if (!complete) remove(this, file);
            Toast.makeText(this, complete ? Strings.pickf("Клип сохранён: %s", "Clip saved: %s", params.getExtras().getString("name"))
                    : Strings.downloadFailed(), Toast.LENGTH_LONG).show();
            jobFinished(params, false);
        });
    }

    @Override public boolean onStopJob(JobParameters params) {
        Call call = running.remove(params.getJobId());
        if (call != null) call.cancel();
        return true;
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
