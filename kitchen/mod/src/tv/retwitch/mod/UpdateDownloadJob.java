package tv.retwitch.mod;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One durable transfer. Serialize writers when Android stops and reschedules a job. */
public final class UpdateDownloadJob extends JobService {
    private static final int ID = 0x7e700111;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AtomicBoolean current;
    private static volatile String progress;

    static boolean scheduled(Context c) {
        return c.getSystemService(JobScheduler.class).getPendingJob(ID) != null;
    }

    static boolean active() { return current != null; }

    static void cancel(Context c) {
        if (current != null) current.set(true);
        current = null;
        progress = null;
        c.getSystemService(JobScheduler.class).cancel(ID);
    }

    static void enqueue(Context c, String request, boolean unmetered) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        PersistableBundle extras = new PersistableBundle();
        extras.putString("request", request);
        JobInfo.Builder job = new JobInfo.Builder(ID, new ComponentName(c, UpdateDownloadJob.class))
                .setRequiredNetworkType(unmetered ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras).setPersisted(true);
        if (!unmetered && Build.VERSION.SDK_INT >= 31) {
            if (jobs.schedule(job.setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS) return;
            job.setExpedited(false);
        }
        if (jobs.schedule(job.build()) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Update job not scheduled");
    }

    static String status(boolean unmetered) {
        String now = progress;
        if (current != null && now != null) return now;
        return unmetered ? Strings.pick("Ожидание сети без тарификации", "Waiting for an unmetered network")
                : Strings.pick("В очереди на загрузку", "Queued for download");
    }

    private JobParameters running;

    @Override public boolean onStartJob(JobParameters params) {
        String request = params.getExtras().getString("request");
        ModUpdate.Release r = ModUpdate.requested(this, request);
        if (r == null) return false;
        if (ModUpdate.ready(this, r)) {
            ModUpdate.finished(this, request, true);
            return false;
        }
        AtomicBoolean stopped = new AtomicBoolean();
        current = stopped;
        running = params;
        progress = null;
        ModUpdate.refresh();
        WORK.execute(() -> {
            boolean verified = false;
            try {
                File part = ModUpdate.file(this, r, true);
                File dir = part.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create update directory");
                long start = SystemClock.elapsedRealtime(), offset = part.length();
                verified = UpdateApk.fetch(part, r.size, r.sha256, ModUpdate.preferred(this), stopped,
                        (source, from) -> ModUpdate.mirror(r, source, from), (source, bytes) -> {
                            if (stopped.get()) return;
                            long elapsed = SystemClock.elapsedRealtime() - start;
                            long left = elapsed > 2000 && bytes > offset ? (r.size - bytes) * elapsed / (bytes - offset) / 1000 : -1;
                            progress = ModUpdate.sourceLabel(source) + " · " + Strings.updateProgress(bytes, r.size, left);
                        });
            } catch (Throwable e) {
                if (!stopped.get()) {
                    Log.w("RTUpdate", "download failed", e);
                    CrashReporter.report("RTUpdate", "download failed", e);
                }
            }
            final boolean done = verified;
            MAIN.post(() -> {
                if (stopped.get() || running != params || current != stopped) return;
                running = null;
                current = null;
                progress = null;
                boolean retry = !done && ModUpdate.retryAutomatically(this, request);
                if (!retry) ModUpdate.finished(this, request, done);
                jobFinished(params, retry);
            });
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (running == params) {
            if (current != null) current.set(true);
            running = null;
            current = null;
            progress = null;
        }
        ModUpdate.refresh();
        return ModUpdate.requested(this, params.getExtras().getString("request")) != null;
    }
}
