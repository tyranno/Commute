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
 */
@Suppress("DEPRECATION")
fun currentWifiSsid(context: Context): String? {
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    return wifiManager.connectionInfo.cleanSsid()
}
