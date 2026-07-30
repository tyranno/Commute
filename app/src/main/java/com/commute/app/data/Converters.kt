package com.commute.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromEventType(type: CommuteEventType): String = type.name

    @TypeConverter
    fun toEventType(value: String): CommuteEventType = CommuteEventType.valueOf(value)

    @TypeConverter
    fun fromLeaveType(type: LeaveType): String = type.name

    @TypeConverter
    fun toLeaveType(value: String): LeaveType = LeaveType.valueOf(value)

    @TypeConverter
    fun fromHolidaySource(source: HolidaySource): String = source.name

    @TypeConverter
    fun toHolidaySource(value: String): HolidaySource = HolidaySource.valueOf(value)
}
