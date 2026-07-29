package com.commute.app.wifi

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.commute.app.data.CompanyNetwork

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
 * Whether *any* registered office Wi-Fi network shows up among nearby access points in the
 * device's last Wi-Fi scan, regardless of whether the phone is actually connected to it. Used for
 * commute detection: the user wants "walked into range" to count as 출근, not "actively connected
 * to that AP" — in range but never associated (Wi-Fi off, on LTE, or connected elsewhere) must
 * still count. This function itself only reads the OS's cached scan results rather than
 * triggering a scan of its own; [com.commute.app.wifi.WifiMonitorService] is the one keeping that
 * cache fresh (see [requestWifiScan]) by requesting one every poll — cheap enough at a 1-minute
 * interval to be worth doing rather than depending entirely on whatever scanning happens to occur
 * elsewhere. Requires ACCESS_FINE_LOCATION.
 */
fun isAnyCompanyNetworkNearby(context: Context, networks: List<CompanyNetwork>): Boolean =
    detectCompanyNetworks(context, networks).nearby

/**
 * Outcome of one presence check.
 *
 * [observedAt] is wall-clock millis for when the OS actually saw the company AP, which is not the
 * same as when we asked. Our poll only runs when the device happens to be awake, while the system's
 * own Wi-Fi scanning keeps running and stamps each result — so on a phone that dozed through the
 * walk into the office, the scan cache already knows the AP appeared several minutes before the
 * poll that reads it. Null when there's no usable stamp (matched via the live connection, or the
 * platform reported a nonsense timestamp), meaning "as of now".
 *
 * [observedAt] is the *earliest* matching scan (first moment of presence, for backdating an 출근);
 * [lastObservedAt] is the *latest* (most recent moment we have positive evidence of presence).
 * The latter is the mirror image for 퇴근: after someone leaves, the AP lingers in the scan cache
 * until the next scan drops it, so the poll that finally reads a miss is minutes late — but that
 * last matching scan's stamp is when they were really still there. Same null meaning as [observedAt].
 *
 * [matchedSsid] is which registered network actually produced the match — display-only (the
 * "회사 와이파이: X" label), never used to decide presence itself. Null when nothing matched.
 * When more than one registered network matches at once (rare — e.g. overlapping coverage from
 * two office networks), whichever was registered first wins the label; presence itself is
 * unaffected either way.
 */
data class CompanyWifiDetection(
    val nearby: Boolean,
    val observedAt: Long?,
    val lastObservedAt: Long?,
    val matchedSsid: String? = null
)

/**
 * [isAnyCompanyNetworkNearby]'s answer plus *when* the evidence for it was captured — see
 * [CompanyWifiDetection.observedAt]. A live connection is reported with a null stamp rather than a
 * backdated one: being associated says nothing about when association began.
 *
 * Multiple [networks] are checked with OR — an office spanning more than one physical network
 * (different floors, a router that got replaced mid-use and now broadcasts a different name, ...)
 * is still "the office" if *any* registered network is in range, the same way Wi-Fi and BLE
 * presence are already OR'd together. Each network's identity is still its **BSSID** set
 * (falling back to SSID-only when a network's BSSID set is empty — the pre-BSSID state for an
 * already-registered network), for the same reason a single network always was: an SSID alone is
 * merely a label, and common defaults like "iptime5G" appear all over the place.
 *
 * Each cached result carries the time of the last scan that saw that BSSID, so across every
 * registered network's APs [observedAt] (the earliest) is the first moment any of them came into
 * view — the moment the person arrived — while [lastObservedAt] (the latest) is the most recent
 * moment any was still in view, which is what a 퇴근 is backdated to. Both stamps are reported;
 * the caller picks the edge it needs.
 *
 * Also counts an actual live connection to any registered network as "nearby" even if the scan
 * cache doesn't (yet) list it — the scan cache can miss a poll cycle while genuinely connected
 * (stale cache, a missed background scan tick, brief AP interference), which showed up in
 * practice as a spurious ~1-minute 자리비움 while the phone never left the office. A real
 * connection is always at least as strong a presence signal as a scan hit, so this only ever
 * adds true positives.
 */
@Suppress("DEPRECATION")
fun detectCompanyNetworks(context: Context, networks: List<CompanyNetwork>): CompanyWifiDetection {
    val notNearby = CompanyWifiDetection(nearby = false, observedAt = null, lastObservedAt = null)
    if (networks.isEmpty()) return notNearby
    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return notNearby

    val connection = wifiManager.connectionInfo
    val connectedSsid = connection.cleanSsid()
    val connectedBssid = connection?.bssid?.normalizeBssid()
    val connectionMatch = networks.firstOrNull { network ->
        connectedSsid == network.ssid && (network.bssids.isEmpty() || connectedBssid in network.bssids)
    }
    if (connectionMatch != null) {
        return CompanyWifiDetection(nearby = true, observedAt = null, lastObservedAt = null, matchedSsid = connectionMatch.ssid)
    }

    val scanResults = wifiManager.scanResults
    var matchedNetwork: CompanyNetwork? = null
    val stamps = mutableListOf<Long>()
    for (network in networks) {
        val matches = scanResults.filter { result ->
            result.SSID?.trim('"') == network.ssid &&
                (network.bssids.isEmpty() || result.BSSID?.normalizeBssid() in network.bssids)
        }
        if (matches.isEmpty()) continue
        if (matchedNetwork == null) matchedNetwork = network
        stamps += matches.mapNotNull { it.wallClockSeenAt() }
    }
    if (matchedNetwork == null) return notNearby
    return CompanyWifiDetection(
        nearby = true,
        observedAt = stamps.minOrNull(),
        lastObservedAt = stamps.maxOrNull(),
        matchedSsid = matchedNetwork.ssid
    )
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
 * A single label to show for potentially several registered networks — status card and
 * notifications only want one name at a glance, not a list. Prefers [matchedSsid] (whichever
 * network is actually detected right now), falling back to the first-registered network so
 * there's still something to show when nothing is currently in range. Null only when no network
 * is registered at all.
 */
fun representativeCompanySsid(networks: List<CompanyNetwork>, matchedSsid: String?): String? =
    matchedSsid ?: networks.firstOrNull()?.ssid

/**
 * Best-effort request for a fresh Wi-Fi scan — used both for the explicit, user-initiated "search
 * for nearby networks to register" flow, and once per poll from [com.commute.app.wifi.WifiMonitorService]
 * to keep the scan cache [detectCompanyNetworks] reads from going stale while not actually connected
 * to the office AP. Neither call site is anywhere near Android 9+'s scan-throttling limits (a single
 * manual tap, or one request per 60s from a running foreground service). The call may still be
 * silently ignored by the OS; callers should treat a fresher read shortly after as a bonus, not a
 * guarantee.
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
