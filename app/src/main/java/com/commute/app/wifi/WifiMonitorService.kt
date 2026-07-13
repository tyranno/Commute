package com.commute.app.wifi

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.SettingsRepository
import java.text.SimpleDateFormat
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
 * observed it (not the poll that first notices the disconnect).
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
            val wasAtWork = settingsRepository.isAtWork.first()
            val now = System.currentTimeMillis()

            if (connectedToCompany) {
                if (!wasAtWork) {
                    recordEvent(CommuteEventType.ARRIVE, companySsid, now)
                    settingsRepository.setIsAtWork(true)
                    showEventNotification(
                        applicationContext,
                        "출근 기록됨",
                        "회사 와이파이($companySsid) 감지 ${timeFormat.format(Date(now))}"
                    )
                }
                settingsRepository.setLastSeenAt(now)
            } else if (wasAtWork) {
                val lastSeen = settingsRepository.lastSeenAt.first() ?: now
                recordEvent(CommuteEventType.LEAVE, companySsid, lastSeen)
                settingsRepository.setIsAtWork(false)
                showEventNotification(
                    applicationContext,
                    "퇴근 기록됨",
                    "회사 와이파이($companySsid) 마지막 감지 ${timeFormat.format(Date(lastSeen))}"
                )
            }
        }
    }

    private suspend fun recordEvent(type: CommuteEventType, ssid: String, timestamp: Long) {
        CommuteDatabase.getInstance(applicationContext).commuteDao().insert(
            CommuteEvent(type = type, ssid = ssid, timestamp = timestamp)
        )
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
