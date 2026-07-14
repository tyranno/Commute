package com.commute.app.wifi

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that polls Wi-Fi connectivity every [CHECK_INTERVAL_MS] and records
 * commute events against the registered company SSID: ARRIVE is stamped with the first
 * poll that observes the company Wi-Fi, LEAVE is stamped with the last poll that still
 * observed it (not the poll that first notices the disconnect). A disconnect shorter than
 * the configured absence threshold (기본 10분, 가산 연구소 운영 방안 기준) is treated as
 * 이석(temporary absence) rather than 퇴근 and does not end the work session. Disconnects
 * that fall inside the configured 점심시간 window get the same treatment regardless of how
 * long they last, plus the normal absence-threshold grace period after the window closes.
 */
class WifiMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var settingsRepository: SettingsRepository
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        ensureNotificationChannels(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // A "location"-type foreground service must already hold the permission at the
            // moment startForeground() is called, or the platform throws a SecurityException
            // (enforced since Android 14). Bail out instead of crashing if the UI raced ahead
            // of the permission grant, or the permission was revoked since this was last enabled.
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(MONITOR_NOTIFICATION_ID, buildMonitorNotification(this, "출퇴근 감지 중"))
        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch {
                while (isActive) {
                    checkWifiState()
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun checkWifiState() {
        stateMutex.withLock {
            val companySsid = settingsRepository.companySsid.first()
            if (companySsid.isNullOrBlank()) return@withLock

            val connectedToCompany = currentWifiSsid(applicationContext) == companySsid
            var wasAtWork = settingsRepository.isAtWork.first()
            val now = System.currentTimeMillis()

            // A session must never silently span a calendar day boundary: if we're still
            // marked "at work" from a previous day (e.g. connected overnight, or monitoring
            // was paused/killed without ever seeing a disconnect), close it out using the
            // last tick we actually observed the company SSID, then fall through so a fresh
            // ARRIVE can be recorded for today if we're still connected.
            if (wasAtWork) {
                val lastSeen = settingsRepository.lastSeenAt.first()
                if (lastSeen != null && !isSameDay(lastSeen, now)) {
                    recordEvent(CommuteEventType.LEAVE, companySsid, lastSeen)
                    settingsRepository.setIsAtWork(false)
                    settingsRepository.clearAwaySinceAt()
                    showEventNotification(
                        applicationContext,
                        "퇴근 기록됨",
                        "날짜 변경으로 자동 마감 ${timeFormat.format(Date(lastSeen))}"
                    )
                    wasAtWork = false
                }
            }

            if (connectedToCompany) {
                if (!wasAtWork) {
                    recordEvent(CommuteEventType.ARRIVE, companySsid, now)
                    settingsRepository.setIsAtWork(true)
                    showEventNotification(
                        applicationContext,
                        "출근 기록됨",
                        "회사 와이파이($companySsid) 감지 ${timeFormat.format(Date(now))}"
                    )
                } else {
                    // Reconnected while still "at work": if we had been watching a disconnect,
                    // it resolved within the absence threshold, so log it as 이석 (not a LEAVE).
                    val awaySince = settingsRepository.awaySinceAt.first()
                    if (awaySince != null) {
                        recordEvent(CommuteEventType.AWAY, companySsid, awaySince, endTimestamp = now)
                        settingsRepository.clearAwaySinceAt()
                        showEventNotification(
                            applicationContext,
                            "이석 종료",
                            "복귀 ${timeFormat.format(Date(now))} (이석 ${minutesBetween(awaySince, now)}분)"
                        )
                    }
                }
                settingsRepository.setLastSeenAt(now)
            } else if (wasAtWork) {
                val awaySince = settingsRepository.awaySinceAt.first()
                if (awaySince == null) {
                    // First tick that notices the disconnect: start the absence grace period
                    // instead of immediately ending the session.
                    settingsRepository.setAwaySinceAt(now)
                } else if (!isWithinLunchWindow(now)) {
                    val thresholdMs = settingsRepository.absenceThresholdMinutes.first() * 60_000L
                    // A disconnect that started before/during lunch gets graced until lunch
                    // officially ends, then the normal grace period on top of that; anything
                    // else is graced from the moment the disconnect was first noticed.
                    val graceStart = lunchEndTimestampIfPassed(awaySince, now) ?: awaySince
                    if (now - graceStart >= thresholdMs) {
                        val lastSeen = settingsRepository.lastSeenAt.first() ?: awaySince
                        recordEvent(CommuteEventType.LEAVE, companySsid, lastSeen)
                        settingsRepository.setIsAtWork(false)
                        settingsRepository.clearAwaySinceAt()
                        showEventNotification(
                            applicationContext,
                            "퇴근 기록됨",
                            "회사 와이파이($companySsid) 마지막 감지 ${timeFormat.format(Date(lastSeen))}"
                        )
                    }
                    // else: still within the grace period, wait for the next poll.
                }
                // else: currently inside the configured lunch window — always wait.
            }
        }
    }

    private suspend fun recordEvent(
        type: CommuteEventType,
        ssid: String,
        timestamp: Long,
        endTimestamp: Long? = null
    ) {
        CommuteDatabase.getInstance(applicationContext).commuteDao().insert(
            CommuteEvent(type = type, ssid = ssid, timestamp = timestamp, endTimestamp = endTimestamp)
        )
    }

    private fun minutesBetween(t1: Long, t2: Long): Long = (t2 - t1) / 60_000L

    private fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private suspend fun isWithinLunchWindow(timestamp: Long): Boolean {
        val (start, end) = lunchWindowMinutes() ?: return false
        val minuteOfDay = minuteOfDay(timestamp)
        return minuteOfDay in start until end
    }

    /**
     * If [awaySince] started before/during a configured lunch window and [now] is past that
     * window's end, returns the window's end timestamp (so the caller measures the grace
     * period from lunch's end rather than from when the disconnect first started).
     */
    private suspend fun lunchEndTimestampIfPassed(awaySince: Long, now: Long): Long? {
        val (_, end) = lunchWindowMinutes() ?: return null
        val lunchEnd = timestampAtMinuteOfDay(awaySince, end)
        return if (awaySince < lunchEnd && now >= lunchEnd) lunchEnd else null
    }

    /** Returns (startMinute, endMinute) if a valid lunch window is configured, else null. */
    private suspend fun lunchWindowMinutes(): Pair<Int, Int>? {
        val start = settingsRepository.lunchStartMinute.first()
        val end = settingsRepository.lunchEndMinute.first()
        return if (start < end) start to end else null
    }

    private fun minuteOfDay(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun timestampAtMinuteOfDay(referenceTimestamp: Long, minuteOfDay: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = referenceTimestamp
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WifiMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiMonitorService::class.java))
        }
    }
}
