package com.commute.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.SettingsRepository
import com.commute.app.wifi.WifiMonitorService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun registerCompanySsid(ssid: String) {
        viewModelScope.launch { settingsRepository.setCompanySsid(ssid) }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMonitoringEnabled(enabled) }
        val app = getApplication<Application>()
        if (enabled) WifiMonitorService.start(app) else WifiMonitorService.stop(app)
    }
}
