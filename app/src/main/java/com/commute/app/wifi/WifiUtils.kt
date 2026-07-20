package com.commute.app.wifi

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock

/** Strips surrounding quotes Android puts around SSIDs and filters out the "unknown" placeholder. */
private fun WifiInfo?.cleanSsid(): String? {
    val raw = this?.ssid ?: return null
    if (raw.isBlank() || raw == WifiManager.UNKNOWN_SSID) return null
    return raw.trim('"')
}

/**
 * Reads the SSID of the currently connected Wi-Fi network directly from [WifiManager].
 * Requires ACCESS_FINE_LOCATION to be granted; returns null otherwise or when not on Wi-Fi.
 * Used for the "register this network as company Wi-Fi" flow, which does require an
 * actual connection at registration time.
 */
@Suppress("DEPRECATION")
fun currentWifiSsid(context: Context): String? {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    return wifiManager.connectionInfo.cleanSsid()
}

/**
 * Whether the company's Wi-Fi shows up among nearby access points in the device's last Wi-Fi
 * scan, regardless of whether the phone is actually connected to it. Used for commute detection:
 * the user wants "walked into range" to count as 출근, not "actively connected to that AP".
 * Reads the OS's cached scan results (populated by the system's own periodic scanning) rather
 * than calling [WifiManager.startScan], which is heavily throttled on Android 9+ and would add
 * little freshness on a 1-minute poll interval anyway. Requires ACCESS_FINE_LOCATION.
 *
 * Identity is the AP's **BSSID** (its hardware MAC), not just the SSID name, whenever
 * [companyBssids] is non-empty. SSID alone is merely a label and common defaults like "iptime5G"
 * appear all over the place — matching on name alone logged a full 출근/퇴근 pair on a day the
 * user never came to the office, because some unrelated AP elsewhere happened to share the name.
 * A BSSID is unique per access point, so this pins detection to the actual office hardware.
 * [companyBssids] holds a set rather than one value because an office usually has several APs
 * broadcasting the same SSID, and roaming between them must not read as leaving.
 *
 * Falls back to SSID-only matching when [companyBssids] is empty — that's the pre-BSSID state
 * for an already-registered network, and silently detecting nothing at all would be far worse
 * than the old imprecise behaviour until the user re-registers.
 *
 * Also counts an actual live connection as "nearby" even if the scan cache doesn't (yet) list
 * it — the scan cache can miss a poll cycle while genuinely connected (stale cache, a missed
 * background scan tick, brief AP interference), which showed up in practice as a spurious
 * ~1-minute 자리비움 while the phone never left the office. A real connection is always at least
 * as strong a presence signal as a scan hit, so this only ever adds true positives.
 */
fun isCompanyWifiNearby(context: Context, companySsid: String, companyBssids: Set<String>): Boolean =
    detectCompanyWifi(context, companySsid, companyBssids).nearby

/**
 * Outcome of one presence check.
 *
 * [observedAt] is wall-clock millis for when the OS actually saw the company AP, which is not the
 * same as when we asked. Our poll only runs when the device happens to be awake, while the system's
 * own Wi-Fi scanning keeps running and stamps each result — so on a phone that dozed through the
 * walk into the office, the scan cache already knows the AP appeared several minutes before the
 * poll that reads it. Null when there's no usable stamp (matched via the live connection, or the
 * platform reported a nonsense timestamp), meaning "as of now".
 */
data class CompanyWifiDetection(val nearby: Boolean, val observedAt: Long?)

/**
 * [isCompanyWifiNearby]'s answer plus *when* the evidence for it was captured — see
 * [CompanyWifiDetection.observedAt]. A live connection is reported with a null stamp rather than a
 * backdated one: being associated says nothing about when association began.
 *
 * The stamp is the earliest of the matching scan results. Each cached result carries the time of
 * the last scan that saw that BSSID, so with several office APs the earliest is the first moment
 * any of them came into view — which is the moment the person arrived. Using the freshest instead
 * would throw away exactly the delay this exists to recover.
 */
@Suppress("DEPRECATION")
fun detectCompanyWifi(
    context: Context,
    companySsid: String,
    companyBssids: Set<String>
): CompanyWifiDetection {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return CompanyWifiDetection(nearby = false, observedAt = null)

    val connection = wifiManager.connectionInfo
    val connectionMatches = connection.cleanSsid() == companySsid &&
        (companyBssids.isEmpty() || connection?.bssid?.normalizeBssid() in companyBssids)
    if (connectionMatches) return CompanyWifiDetection(nearby = true, observedAt = null)

    val matches = wifiManager.scanResults.filter { result ->
        result.SSID?.trim('"') == companySsid &&
            (companyBssids.isEmpty() || result.BSSID?.normalizeBssid() in companyBssids)
    }
    if (matches.isEmpty()) return CompanyWifiDetection(nearby = false, observedAt = null)

    val observedAt = matches.mapNotNull { it.wallClockSeenAt() }.minOrNull()
    return CompanyWifiDetection(nearby = true, observedAt = observedAt)
}

/**
 * Converts a [android.net.wifi.ScanResult.timestamp] — microseconds on the elapsed-realtime clock,
 * not a wall-clock value — into wall-clock millis, by anchoring both clocks in the same breath.
 *
 * Returns null for a stamp that can't be true: zero/negative (some drivers just don't fill it in)
 * or one claiming a scan from after the current boot's uptime. A wrong stamp here would silently
 * backdate an 출근, so anything suspect is dropped in favour of "as of now".
 */
@Suppress("DEPRECATION")
private fun android.net.wifi.ScanResult.wallClockSeenAt(): Long? {
    val elapsedNow = SystemClock.elapsedRealtime()
    val seenElapsedMs = timestamp / 1000
    if (seenElapsedMs <= 0 || seenElapsedMs > elapsedNow) return null
    return System.currentTimeMillis() - (elapsedNow - seenElapsedMs)
}

/** BSSIDs are compared case-insensitively — the OS isn't consistent about the hex case it
 * reports between `connectionInfo` and scan results. */
fun String.normalizeBssid(): String = lowercase()

/**
 * BSSIDs of every currently-visible access point broadcasting [ssid] — an office typically has
 * more than one, so registration captures all of them at once rather than just the AP the phone
 * happens to be associated with. Requires ACCESS_FINE_LOCATION.
 */
@Suppress("DEPRECATION")
fun nearbyBssidsFor(context: Context, ssid: String): Set<String> {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptySet()
    val fromScan = wifiManager.scanResults
        .filter { it.SSID?.trim('"') == ssid }
        .mapNotNull { it.BSSID?.normalizeBssid() }
        .filter { it.isUsableBssid() }
    val connection = wifiManager.connectionInfo
    val fromConnection = if (connection.cleanSsid() == ssid) {
        listOfNotNull(connection?.bssid?.normalizeBssid()).filter { it.isUsableBssid() }
    } else {
        emptyList()
    }
    return (fromScan + fromConnection).toSet()
}

/**
 * Rejects the two placeholder addresses the platform substitutes for a real BSSID: the all-zero
 * one, and `02:00:00:00:00:00`, which `WifiInfo.getBSSID()` returns when location access is
 * degraded. Storing either as a company AP would be silently fatal — the set would be non-empty
 * (so detection stops falling back to SSID matching) yet could never match a real scan result,
 * leaving the office permanently undetectable with nothing in the UI to explain why.
 */
private fun String.isUsableBssid(): Boolean =
    isNotBlank() && this != "00:00:00:00:00:00" && this != "02:00:00:00:00:00"

/**
 * Best-effort request for a fresh Wi-Fi scan. Only meant for the explicit, user-initiated
 * "search for nearby networks to register" flow — unlike [isCompanyWifiNearby]'s background
 * polling, a single manual tap won't hit Android 9+'s scan-throttling limits. The call may be
 * silently ignored by the OS (throttled or otherwise); callers should just re-read
 * [nearbyWifiSsids] shortly after rather than depend on this succeeding.
 */
@Suppress("DEPRECATION")
fun requestWifiScan(context: Context) {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
    wifiManager.startScan()
}

/**
 * Nearby Wi-Fi SSIDs from the device's last scan (strongest signal first, blanks/duplicates
 * removed) — lets the user pick a network to register without needing to connect to it first.
 * Requires ACCESS_FINE_LOCATION.
 */
@Suppress("DEPRECATION")
fun nearbyWifiSsids(context: Context): List<String> {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
    return wifiManager.scanResults
        .sortedByDescending { it.level }
        .mapNotNull { it.SSID?.trim('"') }
        .filter { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
        .distinct()
}
