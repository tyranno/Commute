package com.commute.app.wifi

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

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
@Suppress("DEPRECATION")
fun isCompanyWifiNearby(context: Context, companySsid: String, companyBssids: Set<String>): Boolean {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false

    if (companyBssids.isEmpty()) {
        if (wifiManager.connectionInfo.cleanSsid() == companySsid) return true
        return wifiManager.scanResults.any { result -> result.SSID?.trim('"') == companySsid }
    }

    val connection = wifiManager.connectionInfo
    if (connection.cleanSsid() == companySsid && connection?.bssid?.normalizeBssid() in companyBssids) return true
    return wifiManager.scanResults.any { result ->
        result.SSID?.trim('"') == companySsid && result.BSSID?.normalizeBssid() in companyBssids
    }
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
