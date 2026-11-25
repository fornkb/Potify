package app.potify.offline;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public final class LegacyDownloadService extends Service {
    static boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private DownloadBatch batch;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (batch == null) {
            running = true;
            startForeground(Downloads.NOTIFICATION, Downloads.notification(this, 0));
            batch = new DownloadBatch(this, count -> main.post(() -> {
                if (running && batch != null) getSystemService(NotificationManager.class).notify(
                        Downloads.NOTIFICATION, Downloads.notification(this, count));
            }), () -> main.post(this::stopSelf));
            batch.start();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        if (batch != null) batch.stop();
        batch = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
