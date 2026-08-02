package com.yoann.monapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class BuildForegroundService extends Service {
    public static final String ACTION_START = "html.apk.builder.START";
    public static final String ACTION_UPDATE = "html.apk.builder.UPDATE";
    public static final String ACTION_FINISH = "html.apk.builder.FINISH";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_SUCCESS = "success";

    private static final String CHANNEL_PROGRESS = "apk_generation_progress";
    private static final String CHANNEL_RESULT = "apk_generation_result";
    private static final int PROGRESS_ID = 4207;
    private static final int RESULT_ID = 4208;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26 || notificationManager == null) return;

        NotificationChannel progress = new NotificationChannel(
                CHANNEL_PROGRESS,
                "Génération APK",
                NotificationManager.IMPORTANCE_LOW
        );
        progress.setDescription("Progression de la génération APK");
        progress.setShowBadge(false);

        NotificationChannel result = new NotificationChannel(
                CHANNEL_RESULT,
                "Résultat de la génération APK",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        result.setDescription("Fin ou échec d’une génération APK");

        notificationManager.createNotificationChannel(progress);
        notificationManager.createNotificationChannel(result);
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private Notification buildNotification(
            String channel,
            String title,
            String message,
            boolean ongoing,
            boolean success
    ) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);

        builder.setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(openAppIntent())
                .setOngoing(ongoing)
                .setOnlyAlertOnce(ongoing)
                .setAutoCancel(!ongoing)
                .setColor(success ? Color.parseColor("#3DDC97") : Color.parseColor("#FF6B6B"));

        if (ongoing) builder.setProgress(100, 50, true);
        return builder.build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager manager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (manager != null) {
            wakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HtmlApkBuilder:Generation"
            );
            wakeLock.acquire(60L * 60L * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        String title = intent == null ? "Génération APK en cours" : intent.getStringExtra(EXTRA_TITLE);
        String message = intent == null ? "Compilation GitHub en cours…" : intent.getStringExtra(EXTRA_MESSAGE);
        boolean success = intent == null || intent.getBooleanExtra(EXTRA_SUCCESS, true);

        if (title == null || title.trim().isEmpty()) title = "Génération APK";
        if (message == null || message.trim().isEmpty()) message = "Traitement en cours…";

        if (ACTION_FINISH.equals(action)) {
            releaseWakeLock();
            stopForeground(true);
            if (notificationManager != null) {
                notificationManager.notify(
                        RESULT_ID,
                        buildNotification(CHANNEL_RESULT,title,message,false,success)
                );
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        startForeground(
                PROGRESS_ID,
                buildNotification(CHANNEL_PROGRESS,title,message,true,true)
        );
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
