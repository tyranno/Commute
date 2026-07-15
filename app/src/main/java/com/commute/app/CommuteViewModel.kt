package com.commute.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.DailyWorkStat
import com.commute.app.data.SettingsRepository
import com.commute.app.data.computeDailyWorkStats
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

    /** Total worked minutes for the actual current calendar week (월~일), regardless of which
     * week the chart is currently paged to. */
    val weeklyWorkedMinutes: StateFlow<Long> = dailyWorkStats
        .map { stats ->
            val weekStart = startOfWeek(System.currentTimeMillis())
            stats.filter { it.dayStart >= weekStart }.sumOf { it.workedMinutes }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

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

    private fun minuteTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }
}
