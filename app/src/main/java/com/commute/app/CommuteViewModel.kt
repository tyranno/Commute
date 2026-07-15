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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CommuteViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val dao = CommuteDatabase.getInstance(application).commuteDao()

    val companySsid: StateFlow<String?> = settingsRepository.companySsid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val monitoringEnabled: StateFlow<Boolean> = settingsRepository.monitoringEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isAtWork: StateFlow<Boolean> = settingsRepository.isAtWork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    /** Events from Monday of this week onward, for the 기록 tab's scrollable list. */
    val weekEvents: StateFlow<List<CommuteEvent>> = events
        .map { all -> all.filter { it.timestamp >= startOfWeek(System.currentTimeMillis()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Worked-minutes per day across all recorded history (not just this week), so the 현황
     * tab's chart can page back through past weeks — re-ticked every minute so "today" advances
     * while a session is still open. */
    val dailyWorkStats: StateFlow<List<DailyWorkStat>> = combine(
        events,
        settingsRepository.lunchStartMinute,
        settingsRepository.lunchEndMinute,
        minuteTicker()
    ) { allEvents, lunchStart, lunchEnd, _ ->
        computeDailyWorkStats(allEvents, lunchStart, lunchEnd, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            val weekStart = startOfWeek(System.currentTimeMillis())
            stats.filter { it.dayStart >= weekStart }.sumOf { it.workedMinutes }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** ARRIVE/LEAVE events missing their other half (see [findMissingRecords]) across all
     * recorded history — re-ticked every minute so a still-open ARRIVE stops being treated as
     * "today's ongoing session" (and gets flagged) right after midnight. */
    val missingRecords: StateFlow<List<MissingRecordFlag>> = combine(events, minuteTicker()) { allEvents, _ ->
        findMissingRecords(allEvents, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun registerCompanySsid(ssid: String) {
        viewModelScope.launch { settingsRepository.setCompanySsid(ssid) }
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
                val settings = BackupSettings(
                    companySsid = companySsid.value,
                    monitoringEnabled = monitoringEnabled.value,
                    absenceThresholdMinutes = absenceThresholdMinutes.value,
                    lunchStartMinute = lunchStartMinute.value,
                    lunchEndMinute = lunchEndMinute.value
                )
                val json = buildBackupJson(allEvents, settings, System.currentTimeMillis())
                app.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: throw IllegalStateException("파일을 열 수 없습니다")
                Toast.makeText(app, "백업 완료 (${allEvents.size}건)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(app, "백업 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Replaces all current events and durable settings with the contents of a backup file
     * written by [exportBackup]. Transient service state (isAtWork/lastSeenAt/awaySinceAt)
     * isn't restored — WifiMonitorService re-derives it from live Wi-Fi presence on its next
     * poll, so at most it takes one extra poll cycle for the status card to catch up. */
    fun importBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val json = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: throw IllegalStateException("파일을 읽을 수 없습니다")
                val parsed = parseBackupJson(json)
                dao.deleteAll()
                dao.insertAll(parsed.events)
                parsed.settings.companySsid?.let { settingsRepository.setCompanySsid(it) }
                settingsRepository.setAbsenceThresholdMinutes(parsed.settings.absenceThresholdMinutes)
                settingsRepository.setLunchWindow(parsed.settings.lunchStartMinute, parsed.settings.lunchEndMinute)
                setMonitoringEnabled(parsed.settings.monitoringEnabled)
                Toast.makeText(app, "복원 완료 (${parsed.events.size}건)", Toast.LENGTH_SHORT).show()
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
