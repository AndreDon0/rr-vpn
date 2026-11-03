package com.dream.driftpool;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class UpdateJob extends JobService {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.Map<JobParameters, Future<?>> work = new java.util.HashMap<>();

    @Override public boolean onStartJob(JobParameters parameters) {
        Future<?> task = io.submit(() -> {
            boolean retry = false;
            try { UpdateManager.automatic(this); }
            catch (Exception e) { retry = true; }
            boolean needsRetry = retry;
            main.post(() -> {
                if (work.remove(parameters) != null) jobFinished(parameters, needsRetry);
            });
        });
        work.put(parameters, task);
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Future<?> task = work.remove(parameters);
        if (task != null) task.cancel(true);
        return true;
    }
    @Override public void onDestroy() {
        for (Future<?> task : work.values()) task.cancel(true);
        work.clear(); io.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
