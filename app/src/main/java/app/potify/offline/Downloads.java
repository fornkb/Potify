package app.potify.offline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class Downloads {
    private static final String CHANNEL = "playlist_downloads";
    private static final int JOB = 713;
    static final int NOTIFICATION = 714;

    static boolean start(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs.getPendingJob(JOB) != null) return true;
            JobInfo job = new JobInfo.Builder(JOB, new ComponentName(context, PlaylistJob.class))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            try {
                return jobs.schedule(job) == JobScheduler.RESULT_SUCCESS;
            } catch (IllegalStateException | SecurityException e) {
                return false;
            }
        }
        try {
            context.startForegroundService(new Intent(context, LegacyDownloadService.class));
            return true;
        } catch (IllegalStateException | SecurityException e) {
            return false;
        }
    }

    static void pause(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(JobScheduler.class).cancel(JOB);
        } else {
            context.stopService(new Intent(context, LegacyDownloadService.class));
        }
    }

    static boolean scheduled(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            return context.getSystemService(JobScheduler.class).getPendingJob(JOB) != null;
        }
        return LegacyDownloadService.running;
    }

    static Notification notification(Context context, int count) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Playlist downloads", NotificationManager.IMPORTANCE_LOW));
        var open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, CHANNEL)
                .setContentTitle("Downloading songs")
                .setContentText(count == 0 ? "Preparing tracks…" : "Saved " + count + " tracks")
                .setSmallIcon(R.drawable.ic_download)
                .setContentIntent(open)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
