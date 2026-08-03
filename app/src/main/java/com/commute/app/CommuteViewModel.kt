package com.commute.app

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.commute.app.data.BackupSettings
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CompanyNetwork
import com.commute.app.data.DailyWorkStat
import com.commute.app.data.DiagnosticEvent
import com.commute.app.data.Holiday
import com.commute.app.data.HolidaySource
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
import com.commute.app.data.startOfYear
import com.commute.app.holiday.HolidaySyncStatus
import com.commute.app.holiday.fetchKoreanHolidays
import com.commute.app.update.ReleaseInfo
import com.commute.app.update.UpdateStatus
import com.commute.app.update.currentAppVersionName
import com.commute.app.update.downloadApk
import com.commute.app.update.fetchLatestRelease
import com.commute.app.update.isNewerVersion
import com.commute.app.wifi.WifiMonitorService
import com.commute.app.wifi.nearbyBssidsFor
import java.util.Calendar
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
    private val holidayDao = database.holidayDao()
    private val diagnosticEventDao = database.diagnosticEventDao()

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
        // Auto-sync once per year: if nothing synced/declared for the current calendar year yet
        // (first run ever, or a fresh new year with no resync since), fetch it without waiting for
        // the user to remember to press the 휴일 탭's sync button. A manual resync there still
        // covers everything else (correcting a bad fetch, refreshing next year's data early).
        viewModelScope.launch {
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val hasCurrentYear = withContext(Dispatchers.IO) {
                val yearStart = startOfYear(year)
                val yearEnd = startOfYear(year + 1)
                holidayDao.getAllOnce().any { it.date in yearStart until yearEnd }
            }
            if (!hasCurrentYear) syncHolidays()
        }
    }

    val companyNetworks: StateFlow<List<CompanyNetwork>> = settingsRepository.companyNetworks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bleEnabled: StateFlow<Boolean> = settingsRepository.bleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val companyBeaconIds: StateFlow<List<String>> = settingsRepository.companyBeaconIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Public holidays (synced) and user-declared holidays, date-ascending — feeds the 휴일 탭 and
     * the home chart's per-day highlight. Separate from [leaves]: a holiday doesn't consume leave
     * or carry a time range, it just marks the day. */
    val holidays: StateFlow<List<Holiday>> = holidayDao.observeAll()
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
        mergeLeaveStats(base, leaveList, lunchStart, lunchEnd, System.currentTimeMillis())
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

    /** Registers [ssid] as an office network and pins it to the APs currently broadcasting that
     * name, so a same-named network somewhere else can't later be mistaken for the office. Adds a
     * new entry alongside any already-registered networks rather than replacing them — an office
     * can span more than one physical network — but re-registering an SSID that's already on the
     * list merges freshly-seen BSSIDs into its existing entry instead of duplicating it. */
    fun registerCompanySsid(ssid: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val found = nearbyBssidsFor(app, ssid)
            val current = settingsRepository.companyNetworks.first()
            val existing = current.firstOrNull { it.ssid == ssid }
            val updated = if (existing != null) {
                current.map { if (it.ssid == ssid) it.copy(bssids = it.bssids + found) else it }
            } else {
                current + CompanyNetwork(ssid, found)
            }
            settingsRepository.setCompanyNetworks(updated)
            if (found.isEmpty()) {
                // Registering from a stale scan list while out of range captures nothing, which
                // silently falls back to name-only matching — the exact failure that logged a
                // whole 출근/퇴근 pair off an unrelated "iptime5G". Say so instead of degrading quietly.
                Toast.makeText(app, strings().apNotFoundNameOnly, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Removes a batch of selected office-network rows in one shot. Each entry is (ssid, bssid);
     * bssid == null means the whole network (an SSID-only entry with no individual AP to drop).
     * Batched into a single read-modify-write so selecting several rows and deleting them together
     * can't race — separate launches would each read the same pre-delete list and the last write to
     * land would silently undo the others. */
    fun removeCompanyEntries(entries: Set<Pair<String, String?>>) {
        viewModelScope.launch {
            val wholeNetworkRemovals = entries.filter { it.second == null }.mapTo(mutableSetOf()) { it.first }
            val bssidRemovals = entries.filter { it.second != null }.groupBy({ it.first }, { it.second!! })
            val current = settingsRepository.companyNetworks.first()
            val updated = current
                .filterNot { it.ssid in wholeNetworkRemovals }
                .map { network ->
                    val toRemove = bssidRemovals[network.ssid]
                    if (toRemove == null) network else network.copy(bssids = network.bssids - toRemove.toSet())
                }
            settingsRepository.setCompanyNetworks(updated)
        }
    }

    /** Turns parallel BLE beacon detection on or off. Independent of Wi-Fi — both can run at once,
     * and monitoring keeps working on Wi-Fi alone if this is left off. */
    fun setBleEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBleEnabled(enabled) }
    }

    /** Registers [token] (a beacon's manufacturer payload, e.g. "COMMUTE1") as an additional office
     * beacon — adds it alongside any already-registered ones rather than replacing them, the same
     * way [registerCompanySsid] does for Wi-Fi networks. The MAC isn't stored — it rotates — so the
     * token is the whole identity; re-registering one already on the list is a harmless no-op. */
    fun registerCompanyBeacon(token: String) {
        viewModelScope.launch {
            val current = settingsRepository.companyBeaconIds.first()
            if (token !in current) settingsRepository.setCompanyBeaconIds(current + token)
        }
    }

    /** Un-registers a batch of beacons in one shot, leaving any other registered beacons in place.
     * Batched (rather than one launch per token) for the same reason as [removeCompanyEntries]:
     * separate launches would each read the same pre-delete list and the last write to land would
     * silently undo the others. */
    fun removeCompanyBeacons(tokens: Set<String>) {
        viewModelScope.launch {
            settingsRepository.setCompanyBeaconIds(settingsRepository.companyBeaconIds.first() - tokens)
        }
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

    /** Adds or renames a user-declared holiday. [date] is the row's primary key, so saving under
     * an unchanged date is a plain overwrite; [oldDate] non-null and different means the user moved
     * the entry to a new day, which needs the old row removed first (a REPLACE insert wouldn't
     * touch it, since its key hasn't changed). */
    fun saveHoliday(holiday: Holiday, oldDate: Long? = null) {
        viewModelScope.launch {
            if (oldDate != null && oldDate != holiday.date) {
                holidayDao.delete(Holiday(oldDate, "", holiday.source))
            }
            holidayDao.insert(holiday)
        }
    }

    fun deleteHoliday(holiday: Holiday) {
        viewModelScope.launch { holidayDao.delete(holiday) }
    }

    private val _holidaySyncStatus = MutableStateFlow<HolidaySyncStatus>(HolidaySyncStatus.Idle)
    val holidaySyncStatus: StateFlow<HolidaySyncStatus> = _holidaySyncStatus.asStateFlow()

    /** Refetches this year's and next year's Korean public holidays and replaces every existing
     * [HolidaySource.AUTO] row in that span — [HolidaySource.CUSTOM] rows are left alone
     * unconditionally, and any fetched date that a custom entry already occupies is skipped so a
     * resync can never quietly overwrite something the user declared by hand. */
    fun syncHolidays() {
        viewModelScope.launch {
            _holidaySyncStatus.value = HolidaySyncStatus.Syncing
            _holidaySyncStatus.value = try {
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val fetched = withContext(Dispatchers.IO) { fetchKoreanHolidays(year, year + 1) }
                withContext(Dispatchers.IO) {
                    val customDates = holidayDao.getAllOnce()
                        .filter { it.source == HolidaySource.CUSTOM }
                        .mapTo(mutableSetOf()) { it.date }
                    val toInsert = fetched.filterNot { it.date in customDates }
                    holidayDao.deleteAutoInRange(startOfYear(year), startOfYear(year + 2))
                    holidayDao.insertAll(toInsert)
                }
                HolidaySyncStatus.Success(fetched.size)
            } catch (e: Exception) {
                HolidaySyncStatus.Failed(e.message)
            }
        }
    }

    /** Fills in a record the service missed (e.g. wifi/permission hiccup, phone off). */
    fun addEvent(event: CommuteEvent) {
        // Dispatchers.IO because the journal write is blocking file IO, and an append can trigger a
        // full-file trim — not something to run on the main thread. Same for the edit/exclude paths.
        viewModelScope.launch(Dispatchers.IO) {
            val id = dao.insert(event)
            recoveryJournal.append(event.copy(id = id))
        }
    }

    /** Corrects a misdetected record (wrong type or time) after the fact. */
    fun updateEvent(event: CommuteEvent) {
        viewModelScope.launch(Dispatchers.IO) {
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

    /**
     * Permanently removes records — unlike [excludeEvent], unrecoverable. For rows [excludeEvent]
     * alone can't clean up, e.g. true duplicates left behind by a backup restore: excluding them
     * would keep them sitting in [excludedEvents] forever, and since the recovery journal matches
     * by type+timestamp, leaving their journal lines behind risks a later [recoverFromJournal]
     * resurrecting them. Also drops them from the journal so they stay gone.
     *
     * Deletes and journal rewrite happen in one pass on [Dispatchers.IO]: [RecoveryJournal.remove]
     * rewrites the whole file per call, so removing a few hundred duplicates one-by-one on the main
     * thread — the exact scale this is built for — would freeze the UI long enough to ANR.
     *
     * The closing [RecoveryJournal.reconcile] repairs collateral damage: journal lines are matched
     * by [journalKey] (type+timestamp), which a *true* duplicate shares with the twin that stays in
     * the DB, so removing one strips the survivor's line too. Reconciling against what's actually
     * left puts those lines back instead of silently leaving the survivor unrecoverable.
     */
    fun deleteEventsPermanently(events: List<CommuteEvent>) {
        if (events.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(events)
            recoveryJournal.removeAll(events)
            recoveryJournal.reconcile(dao.getAllOnce())
        }
    }

    private fun setExcluded(event: CommuteEvent, excluded: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
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

    /** Diagnostic 로그 뷰어's data source — Wi-Fi/BLE scan results and 판정 근거 for the calendar
     * day starting at [dayStart] (see [startOfDay]). A fresh Flow per call rather than a cached
     * StateFlow like [events]: this table is queried one day at a time and can hold tens of
     * thousands of rows (one per 60s poll, pruned to a rolling 30-day window), so it isn't kept
     * fully loaded in memory the way the much smaller commute-event history is. */
    fun diagnosticEventsForDay(dayStart: Long): Flow<List<DiagnosticEvent>> =
        diagnosticEventDao.observeBetween(dayStart, dayStart + 24 * 60 * 60 * 1000L)

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
                val allHolidays = holidayDao.getAllOnce()
                // Read straight from the repository rather than the StateFlows' .value — those
                // only hold a value while something is subscribed, so a backup's correctness
                // shouldn't depend on which screen happens to be composed right now.
                val settings = BackupSettings(
                    companyNetworks = settingsRepository.companyNetworks.first(),
                    bleEnabled = settingsRepository.bleEnabled.first(),
                    companyBeaconIds = settingsRepository.companyBeaconIds.first(),
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
                    val json = buildBackupJson(allEvents, allLeaves, allHolidays, settings, System.currentTimeMillis())
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
                // Holidays follow the same wholesale-replace rule as leaves.
                holidayDao.deleteAll()
                holidayDao.insertAll(parsed.holidays)
                // Fold the restored events into the journal (additive) so they're protected going
                // forward too, without dropping journal entries the backup happened to omit.
                withContext(Dispatchers.IO) { recoveryJournal.reconcile(dao.getAllOnce()) }
                settingsRepository.clearSessionState()
                settingsRepository.setCompanyNetworks(parsed.settings.companyNetworks)
                settingsRepository.setBleEnabled(parsed.settings.bleEnabled)
                settingsRepository.setCompanyBeaconIds(parsed.settings.companyBeaconIds)
                settingsRepository.setAbsenceThresholdMinutes(parsed.settings.absenceThresholdMinutes)
                settingsRepository.setAutoLeaveAfterAwayMinutes(parsed.settings.autoLeaveAfterAwayMinutes)
                settingsRepository.setLeaveMarginMinutes(parsed.settings.leaveMarginMinutes)
                settingsRepository.setWorkEndMinute(parsed.settings.workEndMinute)
                settingsRepository.setLunchWindow(parsed.settings.lunchStartMinute, parsed.settings.lunchEndMinute)
                settingsRepository.setShowWeekend(parsed.settings.showWeekend)
                settingsRepository.setHalfDayAmWindow(parsed.settings.halfAmStartMinute, parsed.settings.halfAmEndMinute)
                settingsRepository.setHalfDayPmWindow(parsed.settings.halfPmStartMinute, parsed.settings.halfPmEndMinute)
                setMonitoringEnabled(parsed.settings.monitoringEnabled)
                val apNote = if (parsed.settings.companyNetworks.any { it.bssids.isEmpty() }) {
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
