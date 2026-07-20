package com.commute.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "commute_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val COMPANY_SSID = stringPreferencesKey("company_ssid")
        val COMPANY_BSSIDS = stringSetPreferencesKey("company_bssids")
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val IS_AT_WORK = booleanPreferencesKey("is_at_work")
        val LAST_SEEN_AT = longPreferencesKey("last_seen_at")
        val AWAY_SINCE_AT = longPreferencesKey("away_since_at")
        val PROVISIONAL_AWAY_SINCE_AT = longPreferencesKey("provisional_away_since_at")
        val ABSENCE_THRESHOLD_MINUTES = intPreferencesKey("absence_threshold_minutes")
        val LUNCH_START_MINUTE = intPreferencesKey("lunch_start_minute")
        val LUNCH_END_MINUTE = intPreferencesKey("lunch_end_minute")
        val SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
    }

    val companySsid: Flow<String?> = context.dataStore.data.map { it[Keys.COMPANY_SSID] }

    /** BSSIDs (AP hardware addresses) that count as "the office" — an SSID name alone isn't
     * unique enough, since common defaults like "iptime5G" exist in many buildings. A set, not a
     * single value, because an office usually runs several APs on the same SSID. Empty means
     * "registered before BSSID matching existed", which falls back to SSID-only detection. */
    val companyBssids: Flow<Set<String>> = context.dataStore.data.map { it[Keys.COMPANY_BSSIDS] ?: emptySet() }
    val monitoringEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITORING_ENABLED] ?: false }
    val isAtWork: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_AT_WORK] ?: false }
    val lastSeenAt: Flow<Long?> = context.dataStore.data.map { it[Keys.LAST_SEEN_AT] }

    /** Non-null while a disconnect is being watched to see if it resolves within the absence threshold. */
    val awaySinceAt: Flow<Long?> = context.dataStore.data.map { it[Keys.AWAY_SINCE_AT] }

    /** Non-null while a *single* missed poll is waiting for a second consecutive miss before
     * it's promoted to a real [awaySinceAt] — filters out one-tick blips (brief reassociation,
     * DHCP renewal) that would otherwise show up as spurious ~1-minute 자리비움 records. */
    val provisionalAwaySinceAt: Flow<Long?> = context.dataStore.data.map { it[Keys.PROVISIONAL_AWAY_SINCE_AT] }

    /** 자리비움 인정 기준(분) — 가산 연구소 운영 방안 기본값 10분. 이 미만의 단절은 퇴근이 아니라 자리비움으로 처리. */
    val absenceThresholdMinutes: Flow<Int> = context.dataStore.data.map {
        it[Keys.ABSENCE_THRESHOLD_MINUTES] ?: DEFAULT_ABSENCE_THRESHOLD_MINUTES
    }

    /** 점심시간 시작(자정 기준 분, 기본 11:20~12:20). 이 구간 동안의 단절은 자리비움 인정 기준과 무관하게 퇴근으로 마감하지 않음. */
    val lunchStartMinute: Flow<Int> = context.dataStore.data.map {
        it[Keys.LUNCH_START_MINUTE] ?: DEFAULT_LUNCH_START_MINUTE
    }

    /** 점심시간 종료(자정 기준 분). 종료 후에도 자리비움 인정 기준만큼 추가 유예를 둠. */
    val lunchEndMinute: Flow<Int> = context.dataStore.data.map {
        it[Keys.LUNCH_END_MINUTE] ?: DEFAULT_LUNCH_END_MINUTE
    }

    /** 현황 탭 그래프에 토·일 막대를 표시할지 — 근무일이 보통 월~금이라 기본값은 숨김(false),
     * 주말에 근무한 경우 등 필요할 때만 켜서 확인하는 용도. */
    val showWeekend: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_WEEKEND] ?: false }

    suspend fun setCompanySsid(ssid: String) {
        context.dataStore.edit { it[Keys.COMPANY_SSID] = ssid }
    }

    suspend fun setCompanyBssids(bssids: Set<String>) {
        context.dataStore.edit { it[Keys.COMPANY_BSSIDS] = bssids }
    }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MONITORING_ENABLED] = enabled }
    }

    suspend fun setIsAtWork(atWork: Boolean) {
        context.dataStore.edit { it[Keys.IS_AT_WORK] = atWork }
    }

    suspend fun setLastSeenAt(timestamp: Long) {
        context.dataStore.edit { it[Keys.LAST_SEEN_AT] = timestamp }
    }

    suspend fun setAwaySinceAt(timestamp: Long) {
        context.dataStore.edit { it[Keys.AWAY_SINCE_AT] = timestamp }
    }

    suspend fun clearAwaySinceAt() {
        context.dataStore.edit { it.remove(Keys.AWAY_SINCE_AT) }
    }

    suspend fun setProvisionalAwaySinceAt(timestamp: Long) {
        context.dataStore.edit { it[Keys.PROVISIONAL_AWAY_SINCE_AT] = timestamp }
    }

    suspend fun clearProvisionalAwaySinceAt() {
        context.dataStore.edit { it.remove(Keys.PROVISIONAL_AWAY_SINCE_AT) }
    }

    suspend fun setAbsenceThresholdMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.ABSENCE_THRESHOLD_MINUTES] = minutes }
    }

    suspend fun setLunchWindow(startMinute: Int, endMinute: Int) {
        context.dataStore.edit {
            it[Keys.LUNCH_START_MINUTE] = startMinute
            it[Keys.LUNCH_END_MINUTE] = endMinute
        }
    }

    suspend fun setShowWeekend(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_WEEKEND] = show }
    }

    companion object {
        const val DEFAULT_ABSENCE_THRESHOLD_MINUTES = 10
        const val DEFAULT_LUNCH_START_MINUTE = 11 * 60 + 20 // 11:20 — 실제 운영 중인 점심시간 기준
        const val DEFAULT_LUNCH_END_MINUTE = 12 * 60 + 20 // 12:20
    }
}
