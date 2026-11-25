package app.potify.offline;

import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public final class PlaylistJob extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobParameters current;
    private DownloadBatch batch;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (Build.VERSION.SDK_INT < 34) return false;
        current = params;
        setNotification(params, Downloads.NOTIFICATION, Downloads.notification(this, 0),
                JOB_END_NOTIFICATION_POLICY_REMOVE);
        batch = new DownloadBatch(this, count -> main.post(() -> {
            if (current == params && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                getSystemService(NotificationManager.class).notify(
                        Downloads.NOTIFICATION, Downloads.notification(this, count));
            }
        }), () -> main.post(() -> {
            if (current != params) return;
            current = null;
            batch = null;
            jobFinished(params, false);
        }));
        batch.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (Build.VERSION.SDK_INT < 34) return false;
        if (current == params && batch != null) {
            batch.stop();
            batch = null;
            current = null;
        }
        int reason = params.getStopReason();
        return reason != JobParameters.STOP_REASON_CANCELLED_BY_APP
                && reason != JobParameters.STOP_REASON_USER;
    }
}
