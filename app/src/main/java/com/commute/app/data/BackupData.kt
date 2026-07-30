package com.commute.app.data

import org.json.JSONArray
import org.json.JSONObject

private const val BACKUP_FORMAT_VERSION = 1

/**
 * The durable, user-configured settings worth carrying across an app uninstall/reinstall — not
 * transient session state (isAtWork/lastSeenAt/awaySinceAt/provisionalAwaySinceAt), which
 * [CommuteViewModel.importBackup] resets so the service starts the next poll from a clean slate.
 *
 * This must stay in step with [SettingsRepository.Keys]: anything durable that's missing here is
 * silently lost on restore. [companyBssids] is the cautionary case — it was added to settings
 * long after this class, and without it a restore left the office SSID registered with no APs,
 * which falls back to name-only matching and re-creates the false 출퇴근 records that BSSID
 * matching exists to prevent.
 */
data class BackupSettings(
    val companyNetworks: List<CompanyNetwork>,
    val bleEnabled: Boolean,
    val companyBeaconIds: List<String>,
    val monitoringEnabled: Boolean,
    val absenceThresholdMinutes: Int,
    val autoLeaveAfterAwayMinutes: Int,
    val leaveMarginMinutes: Int,
    val workEndMinute: Int,
    val lunchStartMinute: Int,
    val lunchEndMinute: Int,
    val showWeekend: Boolean,
    val halfAmStartMinute: Int,
    val halfAmEndMinute: Int,
    val halfPmStartMinute: Int,
    val halfPmEndMinute: Int
)

data class ParsedBackup(
    val settings: BackupSettings,
    val events: List<CommuteEvent>,
    val leaves: List<LeaveEntry>,
    val holidays: List<Holiday>
)

/** Serializes all commute events plus durable settings into a JSON backup file the user can
 * save anywhere (Downloads, SD card, a synced cloud folder, ...) — since it lives outside the
 * app's private storage, it survives an uninstall/reinstall that would otherwise wipe the
 * Room DB and DataStore. */
fun buildBackupJson(
    events: List<CommuteEvent>,
    leaves: List<LeaveEntry>,
    holidays: List<Holiday>,
    settings: BackupSettings,
    exportedAt: Long
): String {
    val root = JSONObject()
    root.put("version", BACKUP_FORMAT_VERSION)
    root.put("exportedAt", exportedAt)
    root.put(
        "settings",
        JSONObject().apply {
            put(
                "companyNetworks",
                JSONArray().apply {
                    settings.companyNetworks.forEach { network ->
                        put(
                            JSONObject().apply {
                                put("ssid", network.ssid)
                                put("bssids", JSONArray().apply { network.bssids.forEach { put(it) } })
                            }
                        )
                    }
                }
            )
            put("bleEnabled", settings.bleEnabled)
            put("companyBeaconIds", JSONArray().apply { settings.companyBeaconIds.forEach { put(it) } })
            put("monitoringEnabled", settings.monitoringEnabled)
            put("absenceThresholdMinutes", settings.absenceThresholdMinutes)
            put("autoLeaveAfterAwayMinutes", settings.autoLeaveAfterAwayMinutes)
            put("leaveMarginMinutes", settings.leaveMarginMinutes)
            put("workEndMinute", settings.workEndMinute)
            put("lunchStartMinute", settings.lunchStartMinute)
            put("lunchEndMinute", settings.lunchEndMinute)
            put("showWeekend", settings.showWeekend)
            put("halfAmStartMinute", settings.halfAmStartMinute)
            put("halfAmEndMinute", settings.halfAmEndMinute)
            put("halfPmStartMinute", settings.halfPmStartMinute)
            put("halfPmEndMinute", settings.halfPmEndMinute)
        }
    )
    root.put(
        "events",
        JSONArray().apply {
            events.forEach { event ->
                put(
                    JSONObject().apply {
                        put("type", event.type.name)
                        put("ssid", event.ssid)
                        put("timestamp", event.timestamp)
                        put("endTimestamp", event.endTimestamp ?: JSONObject.NULL)
                        put("excluded", event.excluded)
                    }
                )
            }
        }
    )
    root.put(
        "leaves",
        JSONArray().apply {
            leaves.forEach { leave ->
                put(
                    JSONObject().apply {
                        put("type", leave.type.name)
                        put("date", leave.date)
                        put("startMinute", leave.startMinute ?: JSONObject.NULL)
                        put("endMinute", leave.endMinute ?: JSONObject.NULL)
                        put("note", leave.note)
                    }
                )
            }
        }
    )
    root.put(
        "holidays",
        JSONArray().apply {
            holidays.forEach { holiday ->
                put(
                    JSONObject().apply {
                        put("date", holiday.date)
                        put("name", holiday.name)
                        put("source", holiday.source.name)
                    }
                )
            }
        }
    )
    return root.toString(2)
}

/** Parses a backup file written by [buildBackupJson]. Restored events always get a fresh
 * autogenerated id (the destination table is cleared before inserting, see
 * [CommuteViewModel.importBackup]), so the original row ids don't need to round-trip. */
fun parseBackupJson(json: String): ParsedBackup {
    val root = JSONObject(json)
    val settingsJson = root.getJSONObject("settings")
    val settings = BackupSettings(
        // "companyNetworks" is the current format; a backup written before multi-network support
        // only has the singular "companySsid"/"companyBssids" pair, which becomes a one-entry list
        // here so restoring an old file doesn't silently drop the registered office network.
        companyNetworks = settingsJson.optJSONArray("companyNetworks")?.let { array ->
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                val bssids = entry.optJSONArray("bssids")?.let { b ->
                    (0 until b.length()).mapNotNull { b.optString(it, "").ifBlank { null } }.toSet()
                } ?: emptySet()
                CompanyNetwork(ssid = entry.getString("ssid"), bssids = bssids)
            }
        } ?: run {
            val legacySsid = if (settingsJson.isNull("companySsid")) null
                else settingsJson.optString("companySsid", "").ifBlank { null }
            if (legacySsid == null) emptyList() else {
                val legacyBssids = settingsJson.optJSONArray("companyBssids")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it, "").ifBlank { null } }.toSet()
                } ?: emptySet()
                listOf(CompanyNetwork(legacySsid, legacyBssids))
            }
        },
        // Absent in backups predating BLE support — default to off/unregistered, the same as a
        // fresh install that never opted into beacon detection.
        bleEnabled = settingsJson.optBoolean("bleEnabled", false),
        // "companyBeaconIds" is the current format; a backup written before multi-beacon support
        // only has the singular "companyBeaconId", which becomes a one-entry list here so
        // restoring an old file doesn't silently drop the registered beacon.
        companyBeaconIds = settingsJson.optJSONArray("companyBeaconIds")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it, "").ifBlank { null } }
        } ?: run {
            val legacy = if (settingsJson.isNull("companyBeaconId")) null
                else settingsJson.optString("companyBeaconId", "").ifBlank { null }
            if (legacy == null) emptyList() else listOf(legacy)
        },
        monitoringEnabled = settingsJson.optBoolean("monitoringEnabled", false),
        absenceThresholdMinutes = settingsJson.optInt(
            "absenceThresholdMinutes",
            SettingsRepository.DEFAULT_ABSENCE_THRESHOLD_MINUTES
        ),
        autoLeaveAfterAwayMinutes = settingsJson.optInt(
            "autoLeaveAfterAwayMinutes",
            SettingsRepository.DEFAULT_AUTO_LEAVE_AFTER_AWAY_MINUTES
        ),
        // Absent in older backups — default to the standard 3분 trim rather than 0, so a restore
        // doesn't silently turn the correction off for someone who never touched the setting.
        leaveMarginMinutes = settingsJson.optInt(
            "leaveMarginMinutes",
            SettingsRepository.DEFAULT_LEAVE_MARGIN_MINUTES
        ),
        workEndMinute = settingsJson.optInt("workEndMinute", SettingsRepository.DEFAULT_WORK_END_MINUTE),
        lunchStartMinute = settingsJson.optInt("lunchStartMinute", SettingsRepository.DEFAULT_LUNCH_START_MINUTE),
        lunchEndMinute = settingsJson.optInt("lunchEndMinute", SettingsRepository.DEFAULT_LUNCH_END_MINUTE),
        showWeekend = settingsJson.optBoolean("showWeekend", false),
        // Absent in backups predating the leave feature — fall back to the general-convention defaults.
        halfAmStartMinute = settingsJson.optInt("halfAmStartMinute", SettingsRepository.DEFAULT_HALF_AM_START_MINUTE),
        halfAmEndMinute = settingsJson.optInt("halfAmEndMinute", SettingsRepository.DEFAULT_HALF_AM_END_MINUTE),
        halfPmStartMinute = settingsJson.optInt("halfPmStartMinute", SettingsRepository.DEFAULT_HALF_PM_START_MINUTE),
        halfPmEndMinute = settingsJson.optInt("halfPmEndMinute", SettingsRepository.DEFAULT_HALF_PM_END_MINUTE)
    )
    val eventsJson = root.getJSONArray("events")
    val events = (0 until eventsJson.length()).map { index ->
        val eventJson = eventsJson.getJSONObject(index)
        CommuteEvent(
            id = 0L,
            type = CommuteEventType.valueOf(eventJson.getString("type")),
            ssid = eventJson.getString("ssid"),
            timestamp = eventJson.getLong("timestamp"),
            endTimestamp = if (eventJson.isNull("endTimestamp")) null else eventJson.getLong("endTimestamp"),
            // Absent in backups predating exclusion — an older file's records are all still on record.
            excluded = eventJson.optBoolean("excluded", false)
        )
    }
    // Absent in backups predating the leave feature — an older file simply has no leaves.
    val leavesJson = root.optJSONArray("leaves")
    val leaves = if (leavesJson == null) emptyList() else (0 until leavesJson.length()).map { index ->
        val leaveJson = leavesJson.getJSONObject(index)
        LeaveEntry(
            id = 0L,
            type = LeaveType.valueOf(leaveJson.getString("type")),
            date = leaveJson.getLong("date"),
            startMinute = if (leaveJson.isNull("startMinute")) null else leaveJson.getInt("startMinute"),
            endMinute = if (leaveJson.isNull("endMinute")) null else leaveJson.getInt("endMinute"),
            note = leaveJson.optString("note", "")
        )
    }
    // Absent in backups predating the 휴일 feature — an older file simply has no holidays.
    val holidaysJson = root.optJSONArray("holidays")
    val holidays = if (holidaysJson == null) emptyList() else (0 until holidaysJson.length()).map { index ->
        val holidayJson = holidaysJson.getJSONObject(index)
        Holiday(
            date = holidayJson.getLong("date"),
            name = holidayJson.getString("name"),
            source = HolidaySource.valueOf(holidayJson.getString("source"))
        )
    }
    return ParsedBackup(settings, events, leaves, holidays)
}
