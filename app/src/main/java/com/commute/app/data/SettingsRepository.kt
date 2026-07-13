package com.commute.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "commute_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val COMPANY_SSID = stringPreferencesKey("company_ssid")
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val IS_AT_WORK = booleanPreferencesKey("is_at_work")
    }

    val companySsid: Flow<String?> = context.dataStore.data.map { it[Keys.COMPANY_SSID] }
    val monitoringEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITORING_ENABLED] ?: false }
    val isAtWork: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_AT_WORK] ?: false }

    suspend fun setCompanySsid(ssid: String) {
        context.dataStore.edit { it[Keys.COMPANY_SSID] = ssid }
    }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MONITORING_ENABLED] = enabled }
    }

    suspend fun setIsAtWork(atWork: Boolean) {
        context.dataStore.edit { it[Keys.IS_AT_WORK] = atWork }
    }
}
