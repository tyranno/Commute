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
import com.commute.app.ble.CompanyBeaconDetection
import com.commute.app.ble.detectCompanyBeacon
import com.commute.app.ble.mergePresence
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.RecoveryJournal
import com.commute.app.data.SettingsRepository
import com.commute.app.data.timestampAtMinuteOfDay
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
 * The timestamp an ARRIVE should carry, given the scan evidence's own stamp ([observedAt], null if
 * there is none) and the moment the poll ran ([now]).
 *
 * Polling can only observe an arrival once the device is awake, and in Doze that's up to 9-15
 * minutes late — a real arrival at 08:23 was recorded as 08:30 because that's simply when the alarm
 * fired. The OS scanned in the meantime and stamped when it saw the AP, so [observedAt] recovers
 * the difference.
 *
 * Two floors keep a backdate from inventing history:
 *  - [MAX_ARRIVE_BACKDATE_MS] — a scan result older than this is stale evidence, not an arrival we
 *    slept through, and shifting an 출근 half an hour on a driver's word is worse than being late;
 *  - [lastEventAt], the newest timestamp already on record, so a backdated ARRIVE can never land
 *    before the LEAVE (or AWAY end) of the session preceding it and produce overlapping sessions
 *    that [computeDailyWorkStats][com.commute.app.data.computeDailyWorkStats] would double-count.
 *
 * Top-level (not a method) purely so it's reachable from unit tests without a running Service.
 */
fun arriveTimestamp(observedAt: Long?, now: Long, lastEventAt: Long?): Long {
    if (observedAt == null) return now
    val floor = maxOf(now - MAX_ARRIVE_BACKDATE_MS, lastEventAt ?: Long.MIN_VALUE)
    if (floor >= now) return now
    return observedAt.coerceIn(floor, now)
}

/** See [arriveTimestamp]. */
const val MAX_ARRIVE_BACKDATE_MS = 30 * 60_000L

/**
 * The timestamp a 퇴근(LEAVE) should carry: the last confirmed sighting [lastSeenAt] pulled back by
 * a fixed [marginMinutes].
 *
 * The company AP keeps turning up in scans for a few minutes after the person physically leaves —
 * the scan-cache RF tail — so the last real sighting already sits *later* than the true departure,
 * and stamping a 퇴근 at it credits work that wasn't done (a 19:14 departure logged as 19:18). The
 * margin trims that overshoot. It's a single user-tunable value rather than a computed one because
 * the tail length varies day to day and no per-day estimate is reliable; [marginMinutes] == 0
 * disables the trim entirely.
 *
 * Floored at [floorAt] — the newest instant already on record (the session's own 출근, or a prior
 * event) — so the trim can never drag a 퇴근 back before its 출근 and produce a negative-length or
 * overlapping session that [computeDailyWorkStats][com.commute.app.data.computeDailyWorkStats]
 * would mis-total. Mirrors [arriveTimestamp]'s shape, and is top-level for the same reason:
 * unit-testable without a running Service.
 */
fun leaveTimestamp(lastSeenAt: Long, marginMinutes: Int, floorAt: Long?): Long {
    val floor = floorAt ?: Long.MIN_VALUE
    if (floor >= lastSeenAt) return lastSeenAt
    return (lastSeenAt - marginMinutes * 60_000L).coerceIn(floor, lastSeenAt)
}

/**
 * Whether an absence running since [awaySince] should be closed out as 퇴근 as of [now].
 *
 * Two independent triggers, either of which is enough:
 *  - it has lasted [autoLeaveAfterAwayMinutes] (default 3h). Comfortably longer than any
 *    plausible 자리비움 — lunch, a meeting in another building, an errand — so reaching it means
 *    the person left, not that they stepped out;
 *  - the clock passed [workEndMinute] (default 22:00, the 근무 인정 시간 upper bound) on the day
 *    the absence started. Past that nothing further counts as worked time anyway, so holding the
 *    session open just to run out the 3h wait would only risk it surviving to midnight and being
 *    closed by the cruder day-boundary net instead.
 *
 * Deliberately measured from [awaySince] rather than from the previous poll, so a device that
 * dozed through the entire absence still gets the right answer from its first wake-up poll.
 *
 * Top-level for the same reason as [arriveTimestamp]: unit-testable without a Service.
 */
fun autoLeaveDue(
    awaySince: Long,
    now: Long,
    autoLeaveAfterAwayMinutes: Int,
    workEndMinute: Int
): Boolean {
    if (now - awaySince >= autoLeaveAfterAwayMinutes * 60_000L) return true
    return now >= timestampAtMinuteOfDay(awaySince, workEndMinute)
}

/**
 * Foreground service that polls for the registered company Wi-Fi every [CHECK_INTERVAL_MS] and
 * records commute events: presence is "SSID shows up in a nearby scan" ([isCompanyWifiNearby]),
 * not "phone is actually connected to it" — walking into range is enough, matching how a badge
 * reader works. ARRIVE is stamped with the first poll that observes the company Wi-Fi.
 *
 * A disconnect is provisional until it resolves one way or the other, and *which* way is decided
 * purely by how long it lasts — the same absence is noise, 자리비움, or 퇴근 depending only on
 * when (or whether) the company Wi-Fi shows up again:
 *
 *  - resolved in under the 자리비움 인정 기준 (absence threshold) → pure noise, recorded as
 *    nothing at all, not even a short AWAY entry;
 *  - resolved at or after the threshold → one 자리비움(AWAY) event covering its full real
 *    duration, and the session simply continues rather than splitting into LEAVE + fresh ARRIVE.
 *    [computeDailyWorkStats][com.commute.app.data.computeDailyWorkStats] deducts that span from
 *    worked time (the same way lunch time is deducted);
 *  - never resolved, and it has run for 자동 퇴근 처리 기준 (default 3h) or crossed 근무 인정 시간
 *    종료 (default 22:00) → the person went home. See [autoLeaveDue].
 *
 * The auto-leave stamp is the last moment presence was actually confirmed (lastSeenAt — the last
 * scan that truly saw the AP), not the moment the threshold was reached and not the first missed
 * poll: leaving the office at 18:00 and being detected as gone at 21:00 is still an 18:00 퇴근, and
 * because the scan cache keeps the AP visible for minutes after someone leaves, backdating to the
 * last real sighting is what keeps a 퇴근 from being credited later than it happened. This is what
 * actually records a 퇴근 on a normal day; the day-boundary safety net below only catches sessions
 * that somehow outlived it (monitoring off, phone dead, service killed).
 *
 * Being detected again after an auto-leave is a genuinely new session, so it does record a second
 * ARRIVE for the day — someone who left at 18:00 and came back at 22:30 really did arrive twice,
 * and [computeDailyWorkStats] sums a day's sessions rather than assuming one.
 */
class WifiMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var recoveryJournal: RecoveryJournal
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        recoveryJournal = RecoveryJournal(applicationContext)
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
            val bleEnabled = settingsRepository.bleEnabled.first()
            val beaconId = settingsRepository.companyBeaconId.first()
            val wifiRegistered = !companySsid.isNullOrBlank()
            val bleRegistered = bleEnabled && !beaconId.isNullOrBlank()
            // Nothing to detect against — neither the Wi-Fi nor the beacon is registered.
            if (!wifiRegistered && !bleRegistered) return@withLock

            // The label stored on each event (and shown in notifications). Prefer the Wi-Fi name
            // for continuity with existing records; fall back to the beacon token for a BLE-only setup.
            val presenceLabel = companySsid?.takeUnless { it.isBlank() } ?: beaconId ?: "회사"

            val companyBssids = settingsRepository.companyBssids.first()
            val wifiDetection = if (wifiRegistered) {
                detectCompanyWifi(applicationContext, companySsid!!, companyBssids)
            } else {
                CompanyWifiDetection(nearby = false, observedAt = null, lastObservedAt = null)
            }
            // BLE runs *in parallel* as a backup: either signal seeing the office counts as present,
            // so the poll only reads "away" when both are gone — which is what covers someone who
            // keeps Wi-Fi off (LTE only) but sits by the beacon. Skipped when Wi-Fi already sees the
            // office, since presence is settled either way and a live BLE scan (no OS-cached results,
            // so it has to listen for a few seconds every poll) is pure battery cost once redundant.
            // When it does run it's safe under the alarm poll's 30s wakelock.
            val bleDetection = if (bleRegistered && !wifiDetection.nearby) {
                detectCompanyBeacon(applicationContext, beaconId!!)
            } else {
                CompanyBeaconDetection(nearby = false, observedAt = null, lastObservedAt = null)
            }
            val detection = mergePresence(wifiDetection, bleDetection)
            val companyWifiNearby = detection.nearby
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
                    // Same RF-tail trim as the normal auto-leave below — this close is stamped from
                    // lastSeen too, so it carries the same overshoot. See [leaveTimestamp].
                    val leftAt = leaveTimestamp(
                        lastSeen,
                        settingsRepository.leaveMarginMinutes.first(),
                        latestEventTimestamp()
                    )
                    recordEvent(CommuteEventType.LEAVE, presenceLabel, leftAt)
                    showEventNotification(
                        applicationContext,
                        "퇴근 기록됨",
                        "날짜 변경으로 자동 마감 ${timeFormat.format(Date(leftAt))}"
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
                //
                // Stamped with when the OS scan actually last saw the AP, not when this poll ran —
                // those differ by the scan-cache lag, and this is the value a 퇴근 is backdated to,
                // so it must be the true last moment of presence, never merely "when we noticed".
                // Floored at the previous lastSeenAt so a momentarily stale cache read can't drag
                // the last-seen evidence backwards; capped at now since a scan can't be in the
                // future. Falls back to now when there's no usable stamp (live-connection match).
                val prevSeen = settingsRepository.lastSeenAt.first()
                val seenAt = maxOf(detection.lastObservedAt ?: now, prevSeen ?: Long.MIN_VALUE)
                    .coerceAtMost(now)
                settingsRepository.setLastSeenAt(seenAt)
                if (!wasAtWork) {
                    // Stamped with when the OS actually saw the AP, not when this poll got to run
                    // — those differ by minutes whenever the device was dozing. See
                    // [arriveTimestamp].
                    val arrivedAt = arriveTimestamp(detection.observedAt, now, latestEventTimestamp())
                    settingsRepository.setIsAtWork(true)
                    recordEvent(CommuteEventType.ARRIVE, presenceLabel, arrivedAt)
                    showEventNotification(
                        applicationContext,
                        "출근 기록됨",
                        "회사($presenceLabel) 감지 ${timeFormat.format(Date(arrivedAt))}"
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
                            recordEvent(CommuteEventType.AWAY, presenceLabel, awaySince, endTimestamp = now)
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

                // An absence that goes on long enough stops being 자리비움 and becomes 퇴근,
                // backdated to when it started. Until this fires the session stays open, so a
                // return still resolves as a plain 자리비움 — the decision is only made once
                // there's enough elapsed time to be sure the person isn't coming back.
                val awaySince = settingsRepository.awaySinceAt.first()
                if (awaySince != null &&
                    autoLeaveDue(
                        awaySince,
                        now,
                        settingsRepository.autoLeaveAfterAwayMinutes.first(),
                        settingsRepository.workEndMinute.first()
                    )
                ) {
                    // The *decision* to leave is measured from awaySince (the first miss — long
                    // enough unseen to be sure they're gone), but the *stamp* is lastSeenAt: the
                    // last scan that actually saw the AP, i.e. the last moment they were provably
                    // still here. That's a few minutes earlier than the first miss, because the
                    // scan cache keeps the AP visible after they've physically left — so stamping
                    // the first miss credits work that wasn't done. Backdating to lastSeenAt never
                    // records a 퇴근 later than the real one. (lastSeenAt is guaranteed set here:
                    // isAtWork implies it, per the invariant above; awaySince is only a fallback.)
                    // Trimmed back by the 퇴근 마진 to undo the scan-cache RF tail (default 3분): the
                    // AP lingers in scans a few minutes after the person leaves, so lastSeenAt is
                    // already later than the real departure. Floored at the last recorded event so
                    // the trim can't precede this session's 출근. See [leaveTimestamp].
                    val lastSeen = settingsRepository.lastSeenAt.first() ?: awaySince
                    val leftAt = leaveTimestamp(
                        lastSeen,
                        settingsRepository.leaveMarginMinutes.first(),
                        latestEventTimestamp()
                    )
                    // State first, insert second — same reasoning as the day-boundary LEAVE:
                    // a cancel in between would re-insert an identical LEAVE every poll.
                    settingsRepository.setIsAtWork(false)
                    settingsRepository.clearAwaySinceAt()
                    settingsRepository.clearProvisionalAwaySinceAt()
                    recordEvent(CommuteEventType.LEAVE, presenceLabel, leftAt)
                    showEventNotification(
                        applicationContext,
                        "퇴근 기록됨",
                        "자리비움이 이어져 자동 마감 ${timeFormat.format(Date(leftAt))}"
                    )
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
        val event = CommuteEvent(type = type, ssid = ssid, timestamp = timestamp, endTimestamp = endTimestamp)
        val id = CommuteDatabase.getInstance(applicationContext).commuteDao().insert(event)
        // Mirror to the crash-independent journal so this record survives a later DB wipe.
        recoveryJournal.append(event.copy(id = id))
    }

    /**
     * Newest instant already on record — an AWAY's end when it has one, otherwise its start. Used
     * as the floor for a backdated ARRIVE; null when there's no history at all.
     */
    private suspend fun latestEventTimestamp(): Long? =
        CommuteDatabase.getInstance(applicationContext).commuteDao().getLast()
            ?.let { maxOf(it.timestamp, it.endTimestamp ?: it.timestamp) }

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
