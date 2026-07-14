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
    val endTimestamp: Long? = null
)
