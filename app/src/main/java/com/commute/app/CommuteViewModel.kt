package com.commute.app

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.commute.app.data.BackupSettings
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.DailyWorkStat
import com.commute.app.data.LeaveEntry
import com.commute.app.data.SettingsRepository
import com.commute.app.data.MissingRecordFlag
import com.commute.app.data.RecoveryJournal
import com.commute.app.data.buildBackupJson
import com.commute.app.data.computeDailyWorkStats
import com.commute.app.data.findMissingRecords
import com.commute.app.data.mergeLeaveStats
import com.commute.app.data.overtimeMinutesForWeek
import com.commute.app.data.parseBackupJson
import com.commute.app.data.startOfDay
import com.commute.app.data.startOfWeek
import com.commute.app.update.ReleaseInfo
import com.commute.app.update.UpdateStatus
import com.commute.app.update.currentAppVersionName
import com.commute.app.update.downloadApk
import com.commute.app.update.fetchLatestRelease
import com.commute.app.update.isNewerVersion
import com.commute.app.wifi.WifiMonitorService
import com.commute.app.wifi.nearbyBssidsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommuteViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val database = CommuteDatabase.getInstance(application)
    private val dao = database.commuteDao()
    private val leaveDao = database.leaveDao()

    /** Append-only, DB-independent mirror of every event — the safety net that lets a wiped or
     * partially-lost history be rebuilt (see [RecoveryJournal] and [recoverFromJournal]). */
    private val recoveryJournal = RecoveryJournal(application)

    init {
        // Seed the journal from whatever the DB already holds (first run after this feature ships,
        // or after a direct DB edit) so existing history is protected too, not just events
        // recorded from here on. Additive — never removes anything.
        viewModelScope.launch(Dispatchers.IO) {
            recoveryJournal.reconcile(dao.getAllOnce())
        }
    }

    val companySsid: StateFlow<String?> = settingsRepository.companySsid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val companyBssids: StateFlow<Set<String>> = settingsRepository.companyBssids
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val bleEnabled: StateFlow<Boolean> = settingsRepository.bleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val companyBeaconId: StateFlow<String?> = settingsRepository.companyBeaconId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val monitoringEnabled: StateFlow<Boolean> = settingsRepository.monitoringEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isAtWork: StateFlow<Boolean> = settingsRepository.isAtWork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Non-null while [WifiMonitorService] is watching a disconnect to see if it resolves within
     * the absence threshold — exposed so the status card can tell "just lost the signal" apart
     * from "actually past the configured 자리비움 grace period" instead of flipping to 자리비움
     * the instant the signal drops. */
    val awaySinceAt: StateFlow<Long?> = settingsRepository.awaySinceAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every recorded row, excluded or not — the DB-truth baseline [recoverableCount] compares the
     * journal against. Not read by the UI directly (see [events]/[excludedEvents]). */
    private val allEvents: StateFlow<List<CommuteEvent>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All recorded events *except* excluded ones — everything downstream (기록 리스트, the chart,
     * worked-time totals, missing-record detection) reads from this, so excluding a record removes
     * it from all of those the same way a hard delete used to, without the data loss. */
    val events: StateFlow<List<CommuteEvent>> = allEvents
        .map { all -> all.filterNot { it.excluded } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Records the user has taken out of their history — hidden everywhere [events] is used, but
     * still in the DB and listable here so any of them can be put back. */
    val excludedEvents: StateFlow<List<CommuteEvent>> = allEvents
        .map { all -> all.filter { it.excluded } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many events sit in the recovery journal but are missing from the live DB — records a
     * wipe or partial loss dropped that [recoverFromJournal] can restore. 0 in normal operation;
     * re-derived whenever the DB changes (a recovery drops it back to 0).
     *
     * Compared against [allEvents], not [events]: an excluded record is still physically in the
     * DB, just hidden, and comparing against the filtered list would count every excluded record
     * as "missing" and invite recovering something that was never lost. */
    val recoverableCount: StateFlow<Int> = allEvents
        .map { current -> withContext(Dispatchers.IO) { recoveryJournal.readMissing(current).size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val absenceThresholdMinutes: StateFlow<Int> = settingsRepository.absenceThresholdMinutes
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_ABSENCE_THRESHOLD_MINUTES
        )

    val autoLeaveAfterAwayMinutes: StateFlow<Int> = settingsRepository.autoLeaveAfterAwayMinutes
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_AUTO_LEAVE_AFTER_AWAY_MINUTES
        )

    val leaveMarginMinutes: StateFlow<Int> = settingsRepository.leaveMarginMinutes
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_LEAVE_MARGIN_MINUTES
        )

    val workEndMinute: StateFlow<Int> = settingsRepository.workEndMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_WORK_END_MINUTE)

    val lunchStartMinute: StateFlow<Int> = settingsRepository.lunchStartMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_LUNCH_START_MINUTE)

    val lunchEndMinute: StateFlow<Int> = settingsRepository.lunchEndMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_LUNCH_END_MINUTE)

    val showWeekend: StateFlow<Boolean> = settingsRepository.showWeekend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val halfAmStartMinute: StateFlow<Int> = settingsRepository.halfAmStartMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_HALF_AM_START_MINUTE)
    val halfAmEndMinute: StateFlow<Int> = settingsRepository.halfAmEndMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_HALF_AM_END_MINUTE)
    val halfPmStartMinute: StateFlow<Int> = settingsRepository.halfPmStartMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_HALF_PM_START_MINUTE)
    val halfPmEndMinute: StateFlow<Int> = settingsRepository.halfPmEndMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_HALF_PM_END_MINUTE)

    /** Selected UI language — drives which [Strings] the whole UI renders (see [LocalStrings]). */
    val language: StateFlow<AppLanguage> = settingsRepository.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.SYSTEM)

    /** The string table for the currently-selected language, for toasts shown from here (outside
     * Compose, so [LocalStrings] isn't available). */
    private suspend fun strings(): Strings = stringsFor(settingsRepository.language.first())

    /** Declared 연차/반차/외출 records, newest first — the manual counterpart to [events]. */
    val leaves: StateFlow<List<LeaveEntry>> = leaveDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Worked-minutes per day across all recorded history (not just this week), so the 현황
     * tab's chart can page back through past weeks — re-ticked every minute so "today" advances
     * while a session is still open. */
    private val baseDailyWorkStats: Flow<List<DailyWorkStat>> = combine(
        events,
        settingsRepository.lunchStartMinute,
        settingsRepository.lunchEndMinute,
        settingsRepository.absenceThresholdMinutes,
        minuteTicker()
    ) { allEvents, lunchStart, lunchEnd, absenceThreshold, _ ->
        computeDailyWorkStats(allEvents, lunchStart, lunchEnd, absenceThreshold, System.currentTimeMillis())
    }

    val dailyWorkStats: StateFlow<List<DailyWorkStat>> = combine(
        baseDailyWorkStats,
        leaves,
        settingsRepository.lunchStartMinute,
        settingsRepository.lunchEndMinute
    ) { base, leaveList, lunchStart, lunchEnd ->
        mergeLeaveStats(base, leaveList, lunchStart, lunchEnd)
    }
        // viewModelScope is Main.immediate, so without this the whole history is re-crunched on
        // the UI thread every minute — fine today, but it grows with every recorded day.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Signed 초과근무(+)/부족(−) for the actual current week — daily 실근무(연차/반차/외출 포함)의
     * 8시간 대비 합계. Recomputed whenever stats change (which includes the per-minute tick). */
    val weeklyOvertimeMinutes: StateFlow<Long> = dailyWorkStats
        .map { stats ->
            val now = System.currentTimeMillis()
            overtimeMinutesForWeek(stats, startOfWeek(now), now)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val todayWorkedMinutes: StateFlow<Long> = dailyWorkStats
        .map { stats -> stats.firstOrNull { it.dayStart == startOfDay(System.currentTimeMillis()) }?.creditedMinutes ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Today's worked time with the lunch deduction added back — "how long was I actually
     * present, lunch included" — for the 오늘 근무시간 tile's tap-to-toggle view. Leave credit is
     * added on top so a 연차/반차 day reads the same here as it does in the total. */
    val todayWorkedMinutesIncludingLunch: StateFlow<Long> = dailyWorkStats
        .map { stats ->
            stats.firstOrNull { it.dayStart == startOfDay(System.currentTimeMillis()) }
                ?.let { it.rawSpanMinutes + it.leaveMinutes } ?: 0L
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Total credited minutes (실근무 + 연차/반차/외출) for the actual current calendar week (월~일),
     * regardless of which week the chart is currently paged to. */
    val weeklyWorkedMinutes: StateFlow<Long> = dailyWorkStats
        .map { stats ->
            // Bounded at both ends: a mistyped future date used to add its hours to this week's
            // tile while contributing no bar to the chart, so the two disagreed.
            val weekStart = startOfWeek(System.currentTimeMillis())
            val weekEnd = weekStart + 7 * 24 * 60 * 60 * 1000L
            stats.filter { it.dayStart in weekStart until weekEnd }.sumOf { it.creditedMinutes }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** ARRIVE/LEAVE events missing their other half (see [findMissingRecords]) across all
     * recorded history — re-ticked every minute so a still-open ARRIVE stops being treated as
     * "today's ongoing session" (and gets flagged) right after midnight. */
    val missingRecords: StateFlow<List<MissingRecordFlag>> = combine(events, minuteTicker()) { allEvents, _ ->
        findMissingRecords(allEvents, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Registers [ssid] as the office network and pins it to the APs currently broadcasting that
     * name, so a same-named network somewhere else can't later be mistaken for the office. */
    fun registerCompanySsid(ssid: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            settingsRepository.setCompanySsid(ssid)
            val bssids = nearbyBssidsFor(app, ssid)
            settingsRepository.setCompanyBssids(bssids)
            if (bssids.isEmpty()) {
                // Registering from a stale scan list while out of range captures nothing, which
                // silently falls back to name-only matching — the exact failure that logged a
                // whole 출근/퇴근 pair off an unrelated "iptime5G". Say so instead of degrading quietly.
                Toast.makeText(app, strings().apNotFoundNameOnly, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Adds any currently-visible AP broadcasting the registered SSID to the known-office set —
     * for offices whose other APs weren't in range at registration time, or newly added ones. */
    fun addNearbyCompanyBssids() {
        val app = getApplication<Application>()
        val ssid = companySsid.value ?: return
        viewModelScope.launch {
            val found = nearbyBssidsFor(app, ssid)
            if (found.isEmpty()) {
                Toast.makeText(app, strings().apNotVisible(ssid), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val merged = settingsRepository.companyBssids.first() + found
            settingsRepository.setCompanyBssids(merged)
            Toast.makeText(app, strings().apRegistered(merged.size), Toast.LENGTH_SHORT).show()
        }
    }

    fun removeCompanyBssid(bssid: String) {
        viewModelScope.launch {
            settingsRepository.setCompanyBssids(settingsRepository.companyBssids.first() - bssid)
        }
    }

    /** Turns parallel BLE beacon detection on or off. Independent of Wi-Fi — both can run at once,
     * and monitoring keeps working on Wi-Fi alone if this is left off. */
    fun setBleEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBleEnabled(enabled) }
    }

    /** Registers [token] (a beacon's manufacturer payload, e.g. "COMMUTE1") as the office beacon.
     * The MAC isn't stored — it rotates — so this token is the whole identity. */
    fun registerCompanyBeacon(token: String) {
        viewModelScope.launch { settingsRepository.setCompanyBeaconId(token) }
    }

    fun clearCompanyBeacon() {
        viewModelScope.launch { settingsRepository.clearCompanyBeaconId() }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMonitoringEnabled(enabled) }
        val app = getApplication<Application>()
        if (enabled) WifiMonitorService.start(app) else WifiMonitorService.stop(app)
    }

    fun setAbsenceThresholdMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setAbsenceThresholdMinutes(minutes) }
    }

    fun setAutoLeaveAfterAwayMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setAutoLeaveAfterAwayMinutes(minutes) }
    }

    fun setLeaveMarginMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setLeaveMarginMinutes(minutes) }
    }

    fun setWorkEndMinute(minuteOfDay: Int) {
        viewModelScope.launch { settingsRepository.setWorkEndMinute(minuteOfDay) }
    }

    fun setLunchWindow(startMinute: Int, endMinute: Int) {
        viewModelScope.launch { settingsRepository.setLunchWindow(startMinute, endMinute) }
    }

    fun setShowWeekend(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowWeekend(show) }
    }

    fun setHalfDayAmWindow(startMinute: Int, endMinute: Int) {
        viewModelScope.launch { settingsRepository.setHalfDayAmWindow(startMinute, endMinute) }
    }

    fun setHalfDayPmWindow(startMinute: Int, endMinute: Int) {
        viewModelScope.launch { settingsRepository.setHalfDayPmWindow(startMinute, endMinute) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    /** User-initiated data reset: wipes every commute event, leave entry, and the recovery journal,
     * and clears the live session state so detection restarts cleanly. Detection settings (company
     * Wi-Fi/beacon, rules, language) are deliberately preserved — this is "clear my history", not a
     * factory reset. */
    fun resetAllData() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val s = strings()
            withContext(Dispatchers.IO) {
                dao.deleteAll()
                leaveDao.deleteAll()
                recoveryJournal.clear()
            }
            settingsRepository.clearSessionState()
            Toast.makeText(app, s.resetDone, Toast.LENGTH_SHORT).show()
        }
    }

    fun addLeave(entry: LeaveEntry) {
        viewModelScope.launch { leaveDao.insert(entry) }
    }

    fun updateLeave(entry: LeaveEntry) {
        viewModelScope.launch { leaveDao.update(entry) }
    }

    fun deleteLeave(entry: LeaveEntry) {
        viewModelScope.launch { leaveDao.delete(entry) }
    }

    /** Fills in a record the service missed (e.g. wifi/permission hiccup, phone off). */
    fun addEvent(event: CommuteEvent) {
        viewModelScope.launch {
            val id = dao.insert(event)
            recoveryJournal.append(event.copy(id = id))
        }
    }

    /** Corrects a misdetected record (wrong type or time) after the fact. */
    fun updateEvent(event: CommuteEvent) {
        viewModelScope.launch {
            dao.update(event)
            // Drop the pre-edit line, then log the corrected one, so the old timestamp doesn't
            // linger in the journal as a phantom "recoverable" record.
            recoveryJournal.remove(event)
            recoveryJournal.append(event)
        }
    }

    /**
     * "Deleting" a record from 기록보기 no longer removes its row: it flips [CommuteEvent.excluded],
     * which drops it out of [events] (and everything computed from it) exactly as a hard delete
     * used to, but the row — and whatever the radio actually saw — is still there to bring back
     * with [restoreEvent]. A mis-tap or a change of mind used to be unrecoverable; now it isn't.
     */
    fun excludeEvent(event: CommuteEvent) = setExcluded(event, true)

    /** Brings a record back into 기록보기 and every total computed from it. */
    fun restoreEvent(event: CommuteEvent) = setExcluded(event, false)

    private fun setExcluded(event: CommuteEvent, excluded: Boolean) {
        viewModelScope.launch {
            val updated = event.copy(excluded = excluded)
            dao.update(updated)
            // Content changed (the x flag), so the journal line has to be replaced, not just left —
            // same pattern as updateEvent: drop the stale line, log the current one.
            recoveryJournal.remove(event)
            recoveryJournal.append(updated)
        }
    }

    /** Re-inserts every event the recovery journal still holds but the DB has lost (matched by
     * type+timestamp), rebuilding a wiped or partial history without touching what's already
     * there. No-op when nothing is missing. See [RecoveryJournal]. */
    fun recoverFromJournal() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val s = strings()
            val restored = withContext(Dispatchers.IO) {
                val missing = recoveryJournal.readMissing(dao.getAllOnce())
                missing.forEach { dao.insert(it) }
                missing.size
            }
            val msg = if (restored > 0) s.recoveredFromLog(restored) else s.nothingToRecover
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** Writes every recorded event plus the durable settings to [uri] as JSON — since it's
     * saved outside the app's private storage (wherever the user picks via the system file
     * picker), it survives an uninstall/reinstall that would otherwise wipe the Room DB and
     * DataStore. */
    fun exportBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val s = strings()
            try {
                val allEvents = dao.getAllOnce()
                val allLeaves = leaveDao.getAllOnce()
                // Read straight from the repository rather than the StateFlows' .value — those
                // only hold a value while something is subscribed, so a backup's correctness
                // shouldn't depend on which screen happens to be composed right now.
                val settings = BackupSettings(
                    companySsid = settingsRepository.companySsid.first(),
                    companyBssids = settingsRepository.companyBssids.first(),
                    bleEnabled = settingsRepository.bleEnabled.first(),
                    companyBeaconId = settingsRepository.companyBeaconId.first(),
                    monitoringEnabled = settingsRepository.monitoringEnabled.first(),
                    absenceThresholdMinutes = settingsRepository.absenceThresholdMinutes.first(),
                    autoLeaveAfterAwayMinutes = settingsRepository.autoLeaveAfterAwayMinutes.first(),
                    leaveMarginMinutes = settingsRepository.leaveMarginMinutes.first(),
                    workEndMinute = settingsRepository.workEndMinute.first(),
                    lunchStartMinute = settingsRepository.lunchStartMinute.first(),
                    lunchEndMinute = settingsRepository.lunchEndMinute.first(),
                    showWeekend = settingsRepository.showWeekend.first(),
                    halfAmStartMinute = settingsRepository.halfAmStartMinute.first(),
                    halfAmEndMinute = settingsRepository.halfAmEndMinute.first(),
                    halfPmStartMinute = settingsRepository.halfPmStartMinute.first(),
                    halfPmEndMinute = settingsRepository.halfPmEndMinute.first()
                )
                // Off the main thread: the SAF Uri can point at a cloud provider, so the write is
                // potentially a network round-trip.
                withContext(Dispatchers.IO) {
                    val json = buildBackupJson(allEvents, allLeaves, settings, System.currentTimeMillis())
                    app.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: throw IllegalStateException(s.fileOpenFail)
                }
                Toast.makeText(app, s.backupDone(allEvents.size, allLeaves.size), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(app, s.backupFail(e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Replaces all current events and durable settings with the contents of a backup file
     * written by [exportBackup]. The live session state is wiped rather than carried over — the
     * poll branches on isAtWork/lastSeenAt instead of re-deriving them, so keeping a stale
     * "already at work" across a restore meant the next 출근 was never recorded. */
    fun importBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val s = strings()
            try {
                // Stop the service first: it polls and writes on its own coroutine, and an event
                // inserted between the delete and the insert would survive as an orphan.
                WifiMonitorService.stop(app)
                val parsed = withContext(Dispatchers.IO) {
                    val json = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw IllegalStateException(s.fileReadFail)
                    parseBackupJson(json)
                }
                dao.replaceAll(parsed.events)
                // Leaves are replaced wholesale alongside events — the backup is the authoritative
                // snapshot, same as for events.
                leaveDao.deleteAll()
                leaveDao.insertAll(parsed.leaves)
                // Fold the restored events into the journal (additive) so they're protected going
                // forward too, without dropping journal entries the backup happened to omit.
                withContext(Dispatchers.IO) { recoveryJournal.reconcile(dao.getAllOnce()) }
                settingsRepository.clearSessionState()
                parsed.settings.companySsid?.let { settingsRepository.setCompanySsid(it) }
                settingsRepository.setCompanyBssids(parsed.settings.companyBssids)
                settingsRepository.setBleEnabled(parsed.settings.bleEnabled)
                parsed.settings.companyBeaconId?.let { settingsRepository.setCompanyBeaconId(it) }
                settingsRepository.setAbsenceThresholdMinutes(parsed.settings.absenceThresholdMinutes)
                settingsRepository.setAutoLeaveAfterAwayMinutes(parsed.settings.autoLeaveAfterAwayMinutes)
                settingsRepository.setLeaveMarginMinutes(parsed.settings.leaveMarginMinutes)
                settingsRepository.setWorkEndMinute(parsed.settings.workEndMinute)
                settingsRepository.setLunchWindow(parsed.settings.lunchStartMinute, parsed.settings.lunchEndMinute)
                settingsRepository.setShowWeekend(parsed.settings.showWeekend)
                settingsRepository.setHalfDayAmWindow(parsed.settings.halfAmStartMinute, parsed.settings.halfAmEndMinute)
                settingsRepository.setHalfDayPmWindow(parsed.settings.halfPmStartMinute, parsed.settings.halfPmEndMinute)
                setMonitoringEnabled(parsed.settings.monitoringEnabled)
                val apNote = if (parsed.settings.companyBssids.isEmpty() && parsed.settings.companySsid != null) {
                    s.restoreApNote
                } else {
                    ""
                }
                Toast.makeText(app, s.restoreDone(parsed.events.size, parsed.leaves.size, apNote), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(app, s.restoreFail(e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    /** Checks the app's GitHub releases for a version newer than what's installed. Safe to call
     * repeatedly (e.g. tapping "check again" after [UpdateStatus.Failed]) — it always starts over
     * from [UpdateStatus.Checking] rather than needing to be reset first. */
    fun checkForUpdate() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _updateStatus.value = UpdateStatus.Checking
            _updateStatus.value = try {
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
                val currentVersion = currentAppVersionName(app)
                when {
                    release == null -> UpdateStatus.UpToDate
                    isNewerVersion(release.version, currentVersion) -> UpdateStatus.Available(release)
                    else -> UpdateStatus.UpToDate
                }
            } catch (e: Exception) {
                UpdateStatus.Failed(e.message)
            }
        }
    }

    /** Downloads [release]'s APK, reporting progress through [UpdateStatus.Downloading], then
     * leaves the state at [UpdateStatus.ReadyToInstall] for the user to confirm — the system
     * installer is a whole separate permission-and-confirmation flow, so this never launches it
     * on its own. */
    fun downloadUpdate(release: ReleaseInfo) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _updateStatus.value = UpdateStatus.Downloading(release, 0)
            _updateStatus.value = try {
                val file = withContext(Dispatchers.IO) {
                    downloadApk(app.cacheDir, release.downloadUrl) { percent ->
                        _updateStatus.value = UpdateStatus.Downloading(release, percent)
                    }
                }
                UpdateStatus.ReadyToInstall(file)
            } catch (e: Exception) {
                UpdateStatus.Failed(e.message)
            }
        }
    }

    /** Back to square one for the update card — used when the user backs out of
     * [UpdateStatus.Available]/[UpdateStatus.Failed] instead of following through. */
    fun dismissUpdate() {
        _updateStatus.value = UpdateStatus.Idle
    }

    private fun minuteTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }
}
