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
 * Foreground service that polls for the registered company Wi-Fi every [CHECK_INTERVAL_MS] and
 * records commute events: presence is "SSID shows up in a nearby scan" ([isCompanyWifiNearby]),
 * not "phone is actually connected to it" — walking into range is enough, matching how a badge
 * reader works. ARRIVE is stamped with the first poll that observes the company Wi-Fi.
 *
 * Exactly one ARRIVE and one LEAVE per work day: any disconnect that reconnects the same day —
 * no matter how long it lasted, whether that's 2 minutes or 2 hours — always closes out as a
 * single 자리비움(AWAY) event and the session simply continues; it never splits into a LEAVE
 * followed by a fresh ARRIVE. The 자리비움 인정 기준(absence threshold) setting no longer decides
 * whether to end the session here — [computeDailyWorkStats][com.commute.app.data.computeDailyWorkStats]
 * uses it instead to decide how much of a recorded AWAY span to deduct from worked time (short
 * ones count as work, long ones don't — the same way lunch time is deducted). The only way a
 * LEAVE actually gets recorded is the day-boundary safety net below: a disconnect that's still
 * unresolved when a new calendar day starts is closed out using the last confirmed-connected
 * timestamp.
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

            val companyWifiNearby = isCompanyWifiNearby(applicationContext, companySsid)
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

            if (companyWifiNearby) {
                if (!wasAtWork) {
                    recordEvent(CommuteEventType.ARRIVE, companySsid, now)
                    settingsRepository.setIsAtWork(true)
                    showEventNotification(
                        applicationContext,
                        "출근 기록됨",
                        "회사 와이파이($companySsid) 감지 ${timeFormat.format(Date(now))}"
                    )
                } else {
                    // Reconnected while still "at work": always closes out as 자리비움, no matter
                    // how long the disconnect lasted — the session itself never ends here.
                    val awaySince = settingsRepository.awaySinceAt.first()
                    if (awaySince != null) {
                        recordEvent(CommuteEventType.AWAY, companySsid, awaySince, endTimestamp = now)
                        settingsRepository.clearAwaySinceAt()
                        showEventNotification(
                            applicationContext,
                            "자리비움 종료",
                            "복귀 ${timeFormat.format(Date(now))} (자리비움 ${minutesBetween(awaySince, now)}분)"
                        )
                    }
                }
                settingsRepository.setLastSeenAt(now)
            } else if (wasAtWork) {
                // First tick that notices the disconnect: start watching it. It only ever
                // resolves above (as 자리비움, on reconnect) or via the day-boundary safety net
                // (as 퇴근, if still unresolved when a new day starts) — never here.
                if (settingsRepository.awaySinceAt.first() == null) {
                    settingsRepository.setAwaySinceAt(now)
                }
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
