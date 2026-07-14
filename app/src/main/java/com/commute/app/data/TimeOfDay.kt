package com.commute.app.data

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
