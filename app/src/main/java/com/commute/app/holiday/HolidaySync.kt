package com.commute.app.holiday

import com.commute.app.data.Holiday
import com.commute.app.data.HolidaySource
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import org.json.JSONArray

private const val HOLIDAY_API_BASE = "https://date.nager.at/api/v3/PublicHolidays"
private const val HOLIDAY_COUNTRY_CODE = "KR"

/** Thrown for anything [fetchKoreanHolidays] can't recover from on its own — callers show
 * [message] directly, same convention as [com.commute.app.update.UpdateCheckException]. */
class HolidaySyncException(message: String) : Exception(message)

/**
 * Fetches every public holiday for [year] in South Korea from date.nager.at — a free, key-less
 * public API, so there's no service-key registration to set up before this works (unlike Korea's
 * own data.go.kr 특일정보 API). Blocking (plain [HttpURLConnection]) like the rest of this app's
 * network calls; callers wrap it in `withContext(Dispatchers.IO)`.
 */
fun fetchKoreanHolidays(year: Int): List<Holiday> {
    val url = URL("$HOLIDAY_API_BASE/$year/$HOLIDAY_COUNTRY_CODE")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw HolidaySyncException("HTTP ${connection.responseCode}")
        }
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            val name = entry.optString("localName").ifBlank { entry.optString("name") }
            Holiday(
                date = parseIsoDateToLocalMidnight(entry.getString("date")),
                name = name,
                source = HolidaySource.AUTO
            )
        }
    } catch (e: HolidaySyncException) {
        throw e
    } catch (e: Exception) {
        throw HolidaySyncException(e.message ?: e.javaClass.simpleName)
    } finally {
        connection.disconnect()
    }
}

/** [fromYear]..[toYearInclusive] flattened into one list — a sync fetches the current year plus
 * next year's so the chart still has holiday data when paged a little into the future. */
fun fetchKoreanHolidays(fromYear: Int, toYearInclusive: Int): List<Holiday> =
    (fromYear..toYearInclusive).flatMap { fetchKoreanHolidays(it) }

/** Drives the 공휴일 동기화 card — a linear walk from idle through the fetch, same shape as
 * [com.commute.app.update.UpdateStatus]. */
sealed interface HolidaySyncStatus {
    data object Idle : HolidaySyncStatus
    data object Syncing : HolidaySyncStatus
    data class Success(val count: Int) : HolidaySyncStatus
    data class Failed(val message: String?) : HolidaySyncStatus
}

/** "2026-01-01" -> local-midnight millis for that day, matching every other date stored in this
 * app (see [com.commute.app.data.startOfDay]) rather than UTC midnight, which [java.text
 * .SimpleDateFormat.parse] on this pattern would otherwise produce. */
private fun parseIsoDateToLocalMidnight(isoDate: String): Long {
    val (year, month, day) = isoDate.split("-").map { it.toInt() }
    return Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis
}
