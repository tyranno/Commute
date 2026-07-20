package com.commute.app.wifi

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
 * Exactly one ARRIVE and one LEAVE per work day: a disconnect that reconnects the same day never
 * splits the session into a LEAVE followed by a fresh ARRIVE — it either leaves no trace at all
 * (see below) or closes out as a single 자리비움(AWAY) event, and the session simply continues.
 * 자리비움 *is*, by definition, an absence that reached the configured 자리비움 인정 기준
 * (absence threshold): a disconnect resolved in less than that time is treated as pure noise and
 * recorded as nothing — not even a short AWAY entry — while one that reaches or exceeds it gets
 * recorded with its full real duration. [computeDailyWorkStats][com.commute.app.data.computeDailyWorkStats]
 * then deducts that recorded span from worked time (the same way lunch time is deducted). The
 * only way a LEAVE actually gets recorded is the day-boundary safety net below: a disconnect
 * that's still unresolved when a new calendar day starts is closed out using the last
 * confirmed-connected timestamp.
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
        // Holding ACCESS_FINE_LOCATION is necessary but NOT sufficient for a location-type FGS:
        // Android 14+ also requires the app to be in a while-in-use eligible state, which can't
        // be checked up front. Starting from the background (BootReceiver, or the in-app watchdog
        // once the activity has stopped) therefore throws here — and since this runs on the main
        // thread inside onStartCommand with START_STICKY, an uncaught throw crashed the process
        // and the system immediately restarted it into the same crash. Bail out quietly instead;
        // the watchdog retries the next time the app is genuinely in the foreground.
        try {
            startForeground(MONITOR_NOTIFICATION_ID, buildMonitorNotification(this, "출퇴근 감지 중"))
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Re-armed on every delivery so the chain can't die out.
        scheduleNextPollAlarm()
        if (intent?.action == ACTION_POLL) {
            runOneShotPoll()
        }

        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch {
                while (isActive) {
                    // One bad tick must not take down monitoring for the rest of the day: a
                    // transient DataStore IOException (disk full, corrupt prefs) or a Room
                    // SQLiteException would otherwise reach the default handler and kill the
                    // process, since a SupervisorJob only stops siblings from cancelling.
                    try {
                        checkWifiState()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Skip this tick; the next one re-reads state from scratch.
                    }
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

            val companyBssids = settingsRepository.companyBssids.first()
            val companyWifiNearby = isCompanyWifiNearby(applicationContext, companySsid, companyBssids)
            var wasAtWork = settingsRepository.isAtWork.first()
            val now = System.currentTimeMillis()

            // A session must never silently span a calendar day boundary: if we're still
            // marked "at work" from a previous day (e.g. connected overnight, or monitoring
            // was paused/killed without ever seeing a disconnect), close it out using the
            // last tick we actually observed the company SSID, then fall through so a fresh
            // ARRIVE can be recorded for today if we're still connected.
            if (wasAtWork) {
                val lastSeen = settingsRepository.lastSeenAt.first() ?: settingsRepository.awaySinceAt.first()
                if (lastSeen == null) {
                    // "At work" with no timestamp at all is corrupt state (an older build could
                    // land here if it was killed between the ARRIVE insert and its first
                    // setLastSeenAt). There's no defensible stamp to close the session with, and
                    // leaving it would wedge the app permanently — isAtWork stays true, so no
                    // future ARRIVE is ever recorded. Reset so detection resumes; the orphaned
                    // ARRIVE row surfaces in the 기록 누락 banner for the user to correct.
                    settingsRepository.setIsAtWork(false)
                    settingsRepository.clearAwaySinceAt()
                    settingsRepository.clearProvisionalAwaySinceAt()
                    wasAtWork = false
                } else if (!isSameDay(lastSeen, now)) {
                    // State first, insert second. This coroutine is cancelled whenever the
                    // service stops (every monitoring toggle), and a cancel *between* the two
                    // would otherwise leave isAtWork=true with a stale lastSeenAt — re-inserting
                    // an identical LEAVE on every 60s poll, forever. Losing one event to a cancel
                    // is recoverable via the 기록 누락 banner; an insert loop is not.
                    settingsRepository.setIsAtWork(false)
                    settingsRepository.clearAwaySinceAt()
                    settingsRepository.clearProvisionalAwaySinceAt()
                    recordEvent(CommuteEventType.LEAVE, companySsid, lastSeen)
                    showEventNotification(
                        applicationContext,
                        "퇴근 기록됨",
                        "날짜 변경으로 자동 마감 ${timeFormat.format(Date(lastSeen))}"
                    )
                    wasAtWork = false
                }
            }

            if (companyWifiNearby) {
                // A provisional (single-tick, not yet promoted) miss never became a real
                // disconnect, so there's nothing to record for it — just clear it, whether this
                // poll is a fresh ARRIVE or a reconnect while already at work.
                settingsRepository.clearProvisionalAwaySinceAt()
                // Written before anything else so the invariant "isAtWork implies lastSeenAt is
                // set" can't be broken by a cancel mid-poll: lastSeenAt is the only stamp the
                // day-boundary net can close a session with, and losing it strands the session.
                settingsRepository.setLastSeenAt(now)
                if (!wasAtWork) {
                    settingsRepository.setIsAtWork(true)
                    recordEvent(CommuteEventType.ARRIVE, companySsid, now)
                    showEventNotification(
                        applicationContext,
                        "출근 기록됨",
                        "회사 와이파이($companySsid) 감지 ${timeFormat.format(Date(now))}"
                    )
                } else {
                    // Reconnected while still "at work": the session itself never ends here, but
                    // it's only actually recorded as 자리비움 if the disconnect reached the
                    // configured absence threshold — 자리비움 *is*, by definition, "unseen for at
                    // least that long." Anything shorter is just noise and leaves no record at
                    // all, exactly as if it never happened.
                    val awaySince = settingsRepository.awaySinceAt.first()
                    if (awaySince != null) {
                        val thresholdMs = settingsRepository.absenceThresholdMinutes.first() * 60_000L
                        // Cleared before the insert for the same reason as the LEAVE above — a
                        // cancel in between would leave awaySinceAt set and re-insert the very
                        // same AWAY on every following poll.
                        settingsRepository.clearAwaySinceAt()
                        if (now - awaySince >= thresholdMs) {
                            recordEvent(CommuteEventType.AWAY, companySsid, awaySince, endTimestamp = now)
                            showEventNotification(
                                applicationContext,
                                "자리비움 종료",
                                "복귀 ${timeFormat.format(Date(now))} (자리비움 ${minutesBetween(awaySince, now)}분)"
                            )
                        }
                    }
                }
            } else if (wasAtWork) {
                // A single missed poll doesn't start the away timer by itself — it's just as
                // likely a brief reassociation/DHCP renewal blip as a real disconnect. Only a
                // second consecutive miss promotes it to a real disconnect (backdated to the
                // first miss, so the recorded duration is still accurate); one isolated miss
                // that resolves on the very next poll is treated as pure noise and never
                // recorded at all — which is what actually eliminated the spurious ~1-minute
                // 자리비움 entries an earlier, narrower fix (checking a live connection in
                // addition to the scan cache) didn't fully cover.
                if (settingsRepository.awaySinceAt.first() == null) {
                    val provisionalSince = settingsRepository.provisionalAwaySinceAt.first()
                    if (provisionalSince == null) {
                        settingsRepository.setProvisionalAwaySinceAt(now)
                    } else {
                        settingsRepository.setAwaySinceAt(provisionalSince)
                        settingsRepository.clearProvisionalAwaySinceAt()
                    }
                }
            }
        }
    }

    /**
     * The 60s coroutine loop only ticks while the CPU is awake. A foreground service keeps the
     * *process* alive but does nothing to stop the device suspending, so once the phone dozes
     * (screen off and stationary — i.e. every night and weekend) `delay()` simply stops firing
     * and detection goes silent until something else wakes the device. That is the most likely
     * explanation for arrivals that were recorded late, or not at all, after an idle stretch.
     *
     * `setAndAllowWhileIdle` is delivered even in Doze (throttled to roughly every 9-15 minutes,
     * which is well inside the absence threshold) and grants a brief allowlist window on delivery,
     * so targeting the foreground service directly is permitted here even though a background FGS
     * start normally isn't.
     */
    private fun scheduleNextPollAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + CHECK_INTERVAL_MS,
                pollPendingIntent(this)
            )
        } catch (e: Exception) {
            // Alarm quota exhausted or similar — the awake-path loop still covers the common case.
        }
    }

    /**
     * One extra check for an alarm delivery, under a short wakelock: the alarm only guarantees the
     * device is awake at the moment of delivery, and without holding it the phone can suspend
     * again mid-check, before the Wi-Fi read and its DB write complete.
     */
    private fun runOneShotPoll() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        serviceScope.launch {
            try {
                checkWifiState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same rationale as the loop: one bad tick must not kill monitoring.
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
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
        // Cancel the alarm too, or the chain keeps waking the device (and restarting this
        // service) after the user has deliberately turned monitoring off.
        (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pollPendingIntent(this))
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val ACTION_POLL = "com.commute.app.action.POLL"
        private const val WAKELOCK_TAG = "commute:poll"
        private const val WAKELOCK_TIMEOUT_MS = 30_000L

        private fun pollPendingIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, WifiMonitorService::class.java).setAction(ACTION_POLL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * Best-effort start. Android 12+ rejects starting a foreground service from the
         * background (ForegroundServiceStartNotAllowedException) and 14+ additionally rejects a
         * while-in-use type like `location` unless the app is in an eligible state — neither is
         * knowable up front, so callers can't pre-check. Swallowing the refusal keeps a failed
         * restart from taking the process down with it; the service is retried on the next
         * foreground tick, and [onStartCommand] guards the same failure on its own side.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WifiMonitorService::class.java))
            } catch (e: Exception) {
                // Couldn't start right now — not fatal, and not something the user can act on.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiMonitorService::class.java))
        }
    }
}
