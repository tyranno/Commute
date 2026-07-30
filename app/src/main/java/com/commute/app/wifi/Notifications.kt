package com.commute.app.wifi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.commute.app.MainActivity
import com.commute.app.R
import com.commute.app.Strings

const val MONITOR_CHANNEL_ID = "commute_monitor"
const val EVENT_CHANNEL_ID = "commute_events"
const val MONITOR_NOTIFICATION_ID = 1
private const val EVENT_NOTIFICATION_ID = 1000

fun ensureNotificationChannels(context: Context, strings: Strings) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            MONITOR_CHANNEL_ID,
            strings.channelMonitorName,
            NotificationManager.IMPORTANCE_MIN
        // The always-on foreground-service notification must never contribute a launcher badge —
        // it's a persistent status line, not a new event. Only the event channel badges, and that
        // one is cleared on app entry.
        ).apply { setShowBadge(false) }
    )
    manager.createNotificationChannel(
        NotificationChannel(
            EVENT_CHANNEL_ID,
            strings.channelEventName,
            NotificationManager.IMPORTANCE_DEFAULT
        )
    )
}

/** Opens the app to its home screen when a notification is tapped. NEW_TASK is required from a
 * non-Activity context (a Service here); CLEAR_TOP reuses the existing task/instance instead of
 * stacking a duplicate MainActivity on top if it's already running. */
private fun openAppPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun buildMonitorNotification(context: Context, statusText: String) =
    NotificationCompat.Builder(context, MONITOR_CHANNEL_ID)
        .setContentTitle("Commute")
        .setContentText(statusText)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openAppPendingIntent(context))
        .build()

/** Reuses one fixed notification id so a new event replaces the previous one in the shade
 * instead of piling up — a per-event incrementing id used to leave every past ARRIVE/LEAVE/AWAY
 * notification sitting there forever unless individually swiped away, which is what silently
 * built up the launcher badge count (2, 3, ...) with nothing an app-side "inbox" to explain it. */
fun showEventNotification(context: Context, title: String, text: String) {
    val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(openAppPendingIntent(context))
        .build()
    NotificationManagerCompat.from(context).notify(EVENT_NOTIFICATION_ID, notification)
}

/** Clears the event notification so opening the app dismisses the launcher badge it produced.
 * setAutoCancel only fires when the user *taps* the notification — someone who just opens the
 * app from its icon leaves the record notification (and its badge) sitting there forever. Call
 * this whenever the app comes to the foreground so entering to check clears the badge. */
fun clearEventNotifications(context: Context) {
    NotificationManagerCompat.from(context).cancel(EVENT_NOTIFICATION_ID)
}
