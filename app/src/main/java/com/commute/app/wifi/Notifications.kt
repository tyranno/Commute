package com.commute.app.wifi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.commute.app.R

const val MONITOR_CHANNEL_ID = "commute_monitor"
const val EVENT_CHANNEL_ID = "commute_events"
const val MONITOR_NOTIFICATION_ID = 1
private var eventNotificationId = 1000

fun ensureNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            MONITOR_CHANNEL_ID,
            "출퇴근 감지 서비스",
            NotificationManager.IMPORTANCE_MIN
        )
    )
    manager.createNotificationChannel(
        NotificationChannel(
            EVENT_CHANNEL_ID,
            "출퇴근 기록 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        )
    )
}

fun buildMonitorNotification(context: Context, statusText: String) =
    NotificationCompat.Builder(context, MONITOR_CHANNEL_ID)
        .setContentTitle("Commute")
        .setContentText(statusText)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

fun showEventNotification(context: Context, title: String, text: String) {
    val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(context).notify(eventNotificationId++, notification)
}
