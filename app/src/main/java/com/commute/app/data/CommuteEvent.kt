package com.commute.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CommuteEventType {
    ARRIVE,
    LEAVE,
    /** A disconnect shorter than the configured absence threshold that resolved before becoming a LEAVE. */
    AWAY
}

@Entity(tableName = "commute_events")
data class CommuteEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: CommuteEventType,
    val ssid: String,
    val timestamp: Long,
    /** Only set for AWAY events: when the Wi-Fi was seen again, closing out the absence window. */
    val endTimestamp: Long? = null,
    /**
     * Hidden from the 기록 list and left out of every total, but still on record.
     *
     * Removing a misdetection used to delete the row outright, which made a mis-tap unrecoverable
     * and threw away the only evidence of what the radio actually saw. Excluding instead keeps the
     * row, so it can be listed on demand and put back at any time — the same effect on what the
     * user sees and on worked time, without the data loss.
     */
    val excluded: Boolean = false
)
