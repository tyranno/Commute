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
import com.commute.app.data.SettingsRepository
import com.commute.app.data.MissingRecordFlag
import com.commute.app.data.buildBackupJson
import com.commute.app.data.computeDailyWorkStats
import com.commute.app.data.findMissingRecords
import com.commute.app.data.parseBackupJson
import com.commute.app.data.startOfDay
import com.commute.app.data.startOfWeek
import com.commute.app.wifi.WifiMonitorService
import com.commute.app.wifi.nearbyBssidsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val dao = CommuteDatabase.getInstance(application).commuteDao()

    val companySsid: StateFlow<String?> = settingsRepository.companySsid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val companyBssids: StateFlow<Set<String>> = settingsRepository.companyBssids
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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

    val events: StateFlow<List<CommuteEvent>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val absenceThresholdMinutes: StateFlow<Int> = settingsRepository.absenceThresholdMinutes
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_ABSENCE_THRESHOLD_MINUTES
        )

    val lunchStartMinute: StateFlow<Int> = settingsRepository.lunchStartMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_LUNCH_START_MINUTE)

    val lunchEndMinute: StateFlow<Int> = settingsRepository.lunchEndMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_LUNCH_END_MINUTE)

    val showWeekend: StateFlow<Boolean> = settingsRepository.showWeekend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Worked-minutes per day across all recorded history (not just this week), so the 현황
     * tab's chart can page back through past weeks — re-ticked every minute so "today" advances
     * while a session is still open. */
    val dailyWorkStats: StateFlow<List<DailyWorkStat>> = combine(
        events,
        settingsRepository.lunchStartMinute,
        settingsRepository.lunchEndMinute,
        settingsRepository.absenceThresholdMinutes,
        minuteTicker()
    ) { allEvents, lunchStart, lunchEnd, absenceThreshold, _ ->
        computeDailyWorkStats(allEvents, lunchStart, lunchEnd, absenceThreshold, System.currentTimeMillis())
    }
        // viewModelScope is Main.immediate, so without this the whole history is re-crunched on
        // the UI thread every minute — fine today, but it grows with every recorded day.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayWorkedMinutes: StateFlow<Long> = dailyWorkStats
        .map { stats -> stats.firstOrNull { it.dayStart == startOfDay(System.currentTimeMillis()) }?.workedMinutes ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Today's worked time with the lunch deduction added back — "how long was I actually
     * present, lunch included" — for the 오늘 근무시간 tile's tap-to-toggle view. */
    val todayWorkedMinutesIncludingLunch: StateFlow<Long> = dailyWorkStats
        .map { stats -> stats.firstOrNull { it.dayStart == startOfDay(System.currentTimeMillis()) }?.rawSpanMinutes ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Total worked minutes for the actual current calendar week (월~일), regardless of which
     * week the chart is currently paged to. */
    val weeklyWorkedMinutes: StateFlow<Long> = dailyWorkStats
        .map { stats ->
            // Bounded at both ends: a mistyped future date used to add its hours to this week's
            // tile while contributing no bar to the chart, so the two disagreed.
            val weekStart = startOfWeek(System.currentTimeMillis())
            val weekEnd = weekStart + 7 * 24 * 60 * 60 * 1000L
            stats.filter { it.dayStart in weekStart until weekEnd }.sumOf { it.workedMinutes }
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
                Toast.makeText(app, "AP를 찾지 못해 이름만으로 감지합니다. 회사에서 설정 > 회사 AP 등록을 눌러주세요", Toast.LENGTH_LONG).show()
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
                Toast.makeText(app, "주변에 ${ssid} AP가 보이지 않습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val merged = settingsRepository.companyBssids.first() + found
            settingsRepository.setCompanyBssids(merged)
            Toast.makeText(app, "회사 AP ${merged.size}대 등록됨", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeCompanyBssid(bssid: String) {
        viewModelScope.launch {
            settingsRepository.setCompanyBssids(settingsRepository.companyBssids.first() - bssid)
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

    fun setLunchWindow(startMinute: Int, endMinute: Int) {
        viewModelScope.launch { settingsRepository.setLunchWindow(startMinute, endMinute) }
    }

    fun setShowWeekend(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowWeekend(show) }
    }

    /** Fills in a record the service missed (e.g. wifi/permission hiccup, phone off). */
    fun addEvent(event: CommuteEvent) {
        viewModelScope.launch { dao.insert(event) }
    }

    /** Corrects a misdetected record (wrong type or time) after the fact. */
    fun updateEvent(event: CommuteEvent) {
        viewModelScope.launch { dao.update(event) }
    }

    fun deleteEvent(event: CommuteEvent) {
        viewModelScope.launch { dao.delete(event) }
    }

    /** Writes every recorded event plus the durable settings to [uri] as JSON — since it's
     * saved outside the app's private storage (wherever the user picks via the system file
     * picker), it survives an uninstall/reinstall that would otherwise wipe the Room DB and
     * DataStore. */
    fun exportBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val allEvents = dao.getAllOnce()
                // Read straight from the repository rather than the StateFlows' .value — those
                // only hold a value while something is subscribed, so a backup's correctness
                // shouldn't depend on which screen happens to be composed right now.
                val settings = BackupSettings(
                    companySsid = settingsRepository.companySsid.first(),
                    companyBssids = settingsRepository.companyBssids.first(),
                    monitoringEnabled = settingsRepository.monitoringEnabled.first(),
                    absenceThresholdMinutes = settingsRepository.absenceThresholdMinutes.first(),
                    lunchStartMinute = settingsRepository.lunchStartMinute.first(),
                    lunchEndMinute = settingsRepository.lunchEndMinute.first(),
                    showWeekend = settingsRepository.showWeekend.first()
                )
                // Off the main thread: the SAF Uri can point at a cloud provider, so the write is
                // potentially a network round-trip.
                withContext(Dispatchers.IO) {
                    val json = buildBackupJson(allEvents, settings, System.currentTimeMillis())
                    app.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: throw IllegalStateException("파일을 열 수 없습니다")
                }
                Toast.makeText(app, "백업 완료 (${allEvents.size}건)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(app, "백업 실패: ${e.message}", Toast.LENGTH_SHORT).show()
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
            try {
                // Stop the service first: it polls and writes on its own coroutine, and an event
                // inserted between the delete and the insert would survive as an orphan.
                WifiMonitorService.stop(app)
                val parsed = withContext(Dispatchers.IO) {
                    val json = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw IllegalStateException("파일을 읽을 수 없습니다")
                    parseBackupJson(json)
                }
                dao.replaceAll(parsed.events)
                settingsRepository.clearSessionState()
                parsed.settings.companySsid?.let { settingsRepository.setCompanySsid(it) }
                settingsRepository.setCompanyBssids(parsed.settings.companyBssids)
                settingsRepository.setAbsenceThresholdMinutes(parsed.settings.absenceThresholdMinutes)
                settingsRepository.setLunchWindow(parsed.settings.lunchStartMinute, parsed.settings.lunchEndMinute)
                settingsRepository.setShowWeekend(parsed.settings.showWeekend)
                setMonitoringEnabled(parsed.settings.monitoringEnabled)
                val apNote = if (parsed.settings.companyBssids.isEmpty() && parsed.settings.companySsid != null) {
                    " · 회사 AP 정보가 없어 이름만으로 감지합니다"
                } else {
                    ""
                }
                Toast.makeText(app, "복원 완료 (${parsed.events.size}건)$apNote", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(app, "복원 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun minuteTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }
}
