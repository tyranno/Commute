package com.commute.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CommuteEventType {
    ARRIVE,
    LEAVE
}

@Entity(tableName = "commute_events")
data class CommuteEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: CommuteEventType,
    val ssid: String,
    val timestamp: Long
)
