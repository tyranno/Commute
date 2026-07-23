package com.commute.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A leave/absence the user *declares* for a day, as opposed to the RF-detected 출근/퇴근 events in
 * [CommuteEvent]. The 가산 연구소 운영 방안 doc defines the flexible-work-hour rules but says nothing
 * about 연차/반차/외출 clock windows, so these follow the ordinary Korean workplace convention:
 * a full 연차 is a paid 8-hour day, a 반차 is a paid 4-hour half, and an 외출 is an approved time
 * range that still counts toward worked time (mirroring the doc's 외근/출장 소명 → 근무 인정 rule).
 */
enum class LeaveType(val label: String) {
    /** Full-day annual leave — credited as a whole standard workday. */
    ANNUAL("연차"),
    /** Morning half-day off (work the afternoon) — the leave covers the configured 오전 반차 window. */
    HALF_AM("오전 반차"),
    /** Afternoon half-day off (work the morning) — the leave covers the configured 오후 반차 window. */
    HALF_PM("오후 반차"),
    /** An approved outing for a specific time range, credited as work for its duration. */
    OUTING("외출")
}

@Entity(tableName = "leave_entries")
data class LeaveEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: LeaveType,
    /** Local-midnight timestamp of the day this leave applies to (see [startOfDay]). */
    val date: Long,
    /** Only for [LeaveType.OUTING]: minutes-since-midnight the outing starts. Null for the others,
     * whose ranges come from the configured 반차/연차 windows. */
    val startMinute: Int? = null,
    /** Only for [LeaveType.OUTING]: minutes-since-midnight the outing ends. */
    val endMinute: Int? = null,
    val note: String = ""
)
