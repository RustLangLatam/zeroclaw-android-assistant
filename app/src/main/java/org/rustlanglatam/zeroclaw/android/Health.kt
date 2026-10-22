package org.rustlanglatam.zeroclaw.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * What the assistant needs from Android to work while the app is closed, checked in the background: notification
 * access (when messaging apps are selected), the battery exemption and full-screen intents. Vendor updates or battery
 * "optimizations" can remove them silently. A newly lost one is announced in a notification that opens the app, whose
 * Home screen shows how to restore it ([MainActivity.lacking]); the notification is withdrawn once all are back.
 */
object Health {
    enum class Need(val says: Int) {
        NOTIFICATIONS(R.string.health_notifications),
        BATTERY(R.string.health_battery),
        FULL_SCREEN(R.string.health_full_screen),
    }

    /** Returns what is missing now. */
    fun lacking(context: Context): Set<Need> = buildSet {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (Inbox.apps.isNotEmpty() && !notifications.isNotificationListenerAccessGranted(ComponentName(context, MessageListener::class.java))) add(Need.NOTIFICATIONS)
        if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) add(Need.BATTERY)
        if (Build.VERSION.SDK_INT >= 34 && !notifications.canUseFullScreenIntent()) add(Need.FULL_SCREEN)
    }

    /**
     * Compares what is missing with the previous check (kept in the plain prefs file `health`): posts the notice when
     * something was lost since, and cancels it when nothing is missing. Before setup nothing is announced.
     */
    @Synchronized fun check(context: Context) {
        if (!Guard.setUp) return
        val now = lacking(context)
        val prefs = context.getSharedPreferences("health", Context.MODE_PRIVATE)
        val before = prefs.getStringSet("lacking", emptySet()).orEmpty()
        prefs.edit().putStringSet("lacking", now.map { it.name }.toSet()).apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (now.isEmpty()) return manager.cancel(ID)
        if (now.all { it.name in before }) return
        val text = now.joinToString(" ") { t(it.says) }
        val open = PendingIntent.getActivity(
            context, ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = Chime.channel(context, "health", t(R.string.health_channel), NotificationManager.IMPORTANCE_DEFAULT)
        manager.notify(
            ID,
            Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_mark)
                .setContentTitle(t(R.string.health_title, Policy.ASSISTANT)).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    /** Interval between background checks while the service runs. */
    const val EVERY_MS = 60_000L

    /** A notification id in a range no other notification uses. */
    private const val ID = 400_000
}
