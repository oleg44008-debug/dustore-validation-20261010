package ru.dustore.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Якорь на время долгой работы eX: сборка системы и эмуляция игры.
 *
 * Сам он не делает ничего — работа идёт в потоке приложения. Нужен потому, что телефон
 * замораживает ушедшее в фон приложение через несколько секунд (у Samsung это Freecess):
 * погас экран — встала распаковка, и человек через час находит её на том же месте.
 * Процесс с уведомлением о работе в фоне не замораживают, а человек видит, что
 * эмуляция идёт.
 */
public final class ExService extends Service {

    private static final String CHANNEL = "ex";
    private static final int ID = 7;

    /** Поднять якорь с подписью в уведомлении. Повторный вызов только меняет подпись. */
    static void hold(Context context, String text) {
        Intent intent = new Intent(context, ExService.class);
        intent.putExtra("text", text);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception refused) {
            // Без якоря работа всё равно идёт — просто не переживёт ухода в фон.
        }
    }

    static void release(Context context) {
        try {
            context.stopService(new Intent(context, ExService.class));
        } catch (Exception ignored) {
            // Уже остановлен.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra("text");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);

            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "eX",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Сборка системы и эмуляция игр");
                manager.createNotificationChannel(channel);
            }
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        Notification notification = builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("eX работает")
                .setContentText(text == null ? "Не закрывайте DustoreX" : text)
                .setContentIntent(tap)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, notification);
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
