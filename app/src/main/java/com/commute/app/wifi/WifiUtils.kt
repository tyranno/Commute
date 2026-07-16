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
 * Whether [companySsid] shows up among nearby access points in the device's last Wi-Fi scan,
 * regardless of whether the phone is actually connected to it. Used for commute detection:
 * the user wants "walked into range" to count as 출근, not "actively connected to that AP".
 * Reads the OS's cached scan results (populated by the system's own periodic scanning) rather
 * than calling [WifiManager.startScan], which is heavily throttled on Android 9+ and would add
 * little freshness on a 1-minute poll interval anyway. Requires ACCESS_FINE_LOCATION.
 *
 * Also counts an actual live connection to [companySsid] as "nearby" even if the scan cache
 * doesn't (yet) list it — the scan cache can miss a poll cycle while genuinely connected (stale
 * cache, a missed background scan tick, brief AP interference), which showed up in practice as a
 * spurious ~1-minute 자리비움 while the phone never left the office. A real connection is always
 * at least as strong a presence signal as a scan hit, so this only ever adds true positives.
 */
@Suppress("DEPRECATION")
fun isCompanyWifiNearby(context: Context, companySsid: String): Boolean {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
    if (wifiManager.connectionInfo.cleanSsid() == companySsid) return true
    return wifiManager.scanResults.any { result -> result.SSID?.trim('"') == companySsid }
}

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
