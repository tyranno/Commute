package com.commute.app.data

import java.util.Calendar

/** Parses "HH:mm" into minutes since midnight (0..1439), or null if not a valid time. */
fun parseHHmmToMinuteOfDay(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** Formats minutes since midnight back into "HH:mm". */
fun formatMinuteOfDayToHHmm(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    return "%02d:%02d".format(hour, minute)
}

/** The timestamp for [minuteOfDay] on the same calendar day as [referenceTimestamp]. */
fun timestampAtMinuteOfDay(referenceTimestamp: Long, minuteOfDay: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = referenceTimestamp
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** Whether [timestamp]'s time-of-day falls within [startMinute, endMinute), e.g. to check if
 * "now" is inside the configured 점심시간 window. */
fun isWithinMinuteOfDayWindow(timestamp: Long, startMinute: Int, endMinute: Int): Boolean {
    if (startMinute >= endMinute) return false
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return minuteOfDay in startMinute until endMinute
}
