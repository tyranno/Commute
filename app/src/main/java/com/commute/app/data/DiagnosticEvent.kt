package com.commute.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What [com.commute.app.wifi.WifiMonitorService]'s decision engine saw and did on one poll —
 * Wi-Fi/BLE scan results plus whatever 출근/자리비움/퇴근 판정 fired (or didn't). Lets a bad
 * recording (e.g. a 퇴근 timestamp landing right next to its 출근) be traced back to what detection
 * actually reported at the time, not just the [CommuteEvent] it produced.
 *
 * Distinct from [RecoveryJournal]: that's a disaster-recovery mirror of committed [CommuteEvent]
 * rows, kept forever so history survives a wipe. This is a diagnostic trace of the judgment process
 * itself — written every poll regardless of whether anything was recorded — so it's pruned on a
 * rolling window instead (see `DIAGNOSTIC_RETENTION_MS` in `WifiMonitorService`).
 */
@Entity(tableName = "diagnostic_events", indices = [Index(value = ["timestamp"])])
data class DiagnosticEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val wifiNearby: Boolean,
    val wifiSsid: String?,
    val bleNearby: Boolean,
    val bleToken: String?,
    /** True when the BLE scan didn't run this poll at all (no beacon registered, or skipped
     * because Wi-Fi already confirmed presence) — [bleNearby] is meaningless in that case, so the
     * viewer must show "skipped", not "not detected". */
    val bleSkipped: Boolean,
    val wasAtWork: Boolean,
    val isAtWorkAfter: Boolean,
    /** null when this poll changed nothing. */
    val action: String?,
    val reason: String?
)

/** Machine-readable values for [DiagnosticEvent.action] — kept as constants since both the writer
 * ([com.commute.app.wifi.WifiMonitorService]) and the viewer (`DiagnosticLogScreen`) must agree on
 * the exact string. */
object DiagnosticAction {
    const val ARRIVE = "ARRIVE"
    const val AWAY = "AWAY"
    const val LEAVE = "LEAVE"
    const val AWAY_REVERTED = "AWAY_REVERTED"
}

/** Machine-readable values for [DiagnosticEvent.reason]. See [DiagnosticAction]. */
object DiagnosticReason {
    const val FRESH_ARRIVE = "FRESH_ARRIVE"
    const val DAY_BOUNDARY = "DAY_BOUNDARY"
    const val AUTO_LEAVE_TIMEOUT = "AUTO_LEAVE_TIMEOUT"
    const val AUTO_LEAVE_WORKEND = "AUTO_LEAVE_WORKEND"
    const val THRESHOLD_MET = "THRESHOLD_MET"
    const val RETURNED_SAME_DAY = "RETURNED_SAME_DAY"
    const val PROVISIONAL_MISS = "PROVISIONAL_MISS"
    const val AWAY_STARTED = "AWAY_STARTED"
    const val RESET_CORRUPT_STATE = "RESET_CORRUPT_STATE"
}
