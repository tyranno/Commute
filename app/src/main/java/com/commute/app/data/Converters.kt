package com.commute.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromEventType(type: CommuteEventType): String = type.name

    @TypeConverter
    fun toEventType(value: String): CommuteEventType = CommuteEventType.valueOf(value)
}
