package net.sourceforge.servestream.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import net.sourceforge.servestream.R;

/** Notification channel for alarms (required on Android 8+). */
final class AlarmNotifications {
    static final String CHANNEL_ID = "alarm";

    private AlarmNotifications() {
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.alarm_clock_label), NotificationManager.IMPORTANCE_HIGH);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            return new Notification.Builder(context, CHANNEL_ID)
                    .setCategory(Notification.CATEGORY_ALARM);
        }
        return new Notification.Builder(context);
    }
}
