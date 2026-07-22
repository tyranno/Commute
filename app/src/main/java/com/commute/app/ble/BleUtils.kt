package com.commute.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.commute.app.wifi.CompanyWifiDetection
import java.util.Collections
import kotlinx.coroutines.delay

/**
 * The Bluetooth SIG "company identifier" the office beacon advertises its payload under. 0xFFFF is
 * the reserved id for testing/no-registration use, which is what the laptop/ESP32 beacon uses — see
 * doc/ble-beacon. Detection matches on this id *plus* the payload token, never on the advertiser's
 * MAC: Windows (and Android peripherals) rotate the advertising address every few minutes for
 * privacy, so a MAC captured at registration time stops matching within the hour. The manufacturer
 * payload is the stable identity, exactly the way an SSID label is for Wi-Fi.
 */
const val BEACON_COMPANY_ID = 0xFFFF

/** Live foreground scan window. Long enough to catch at least one advertising interval (beacons
 * broadcast every ~100ms–1s) without dominating the 60s poll or the 30s poll wakelock. */
const val DEFAULT_BEACON_SCAN_MILLIS = 6_000L

/** Shorter window for the interactive "search for nearby beacons to register" flow — the user is
 * watching a spinner, so responsiveness matters more than squeezing out a marginal late sighting. */
const val SEARCH_BEACON_SCAN_MILLIS = 4_000L

/**
 * Outcome of one BLE presence check — the beacon analogue of [CompanyWifiDetection], carrying the
 * same [observedAt] (earliest sighting, for backdating an 출근) and [lastObservedAt] (latest
 * sighting, for backdating a 퇴근) so the two signals can be merged by [mergePresence] and fed
 * through the exact same arrive/away/leave machinery. Both stamps are null when nothing matched.
 */
data class CompanyBeaconDetection(val nearby: Boolean, val observedAt: Long?, val lastObservedAt: Long?)

/**
 * Combined presence across Wi-Fi and BLE: present if *either* signal sees the office, so the two
 * are genuinely redundant — losing one (out of Wi-Fi range but at your desk near the beacon, or
 * vice versa) doesn't read as leaving. [observedAt] is the earliest first-sighting across both (the
 * true arrival moment) and [lastObservedAt] the latest last-sighting across both (the last moment
 * any evidence of presence existed, which a 퇴근 is backdated to). Mirrors [CompanyWifiDetection]'s
 * shape so [com.commute.app.wifi.WifiMonitorService] can consume it unchanged.
 */
data class PresenceDetection(val nearby: Boolean, val observedAt: Long?, val lastObservedAt: Long?)

/** Merges a Wi-Fi and a BLE reading into a single presence verdict. Top-level and pure so it's
 * unit-testable without a running Service or a real radio. */
fun mergePresence(wifi: CompanyWifiDetection, ble: CompanyBeaconDetection): PresenceDetection =
    PresenceDetection(
        nearby = wifi.nearby || ble.nearby,
        observedAt = listOfNotNull(wifi.observedAt, ble.observedAt).minOrNull(),
        lastObservedAt = listOfNotNull(wifi.lastObservedAt, ble.lastObservedAt).maxOrNull()
    )

/**
 * Runtime permissions required before a BLE scan can produce office-location evidence. Android 12+
 * split scanning into BLUETOOTH_SCAN, but this app still derives physical location from beacon
 * sightings, so ACCESS_FINE_LOCATION must remain granted too.
 */
fun requiredBleScanPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Whether the app currently holds every permission BLE scanning needs. */
fun hasBleScanPermission(context: Context): Boolean =
    requiredBleScanPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

/** Whether a usable Bluetooth radio exists and is turned on — a scan silently returns nothing when
 * the adapter is off, so callers use this to tell "no beacon nearby" apart from "Bluetooth is off". */
fun isBluetoothOn(context: Context): Boolean {
    val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return adapter != null && adapter.isEnabled
}

/**
 * Runs one bounded BLE scan and reports whether the office beacon [beaconToken] (its manufacturer
 * payload under [BEACON_COMPANY_ID]) was seen, and when. Unlike the Wi-Fi path there's no OS-cached
 * scan to read — BLE has to actively listen — so this suspends for [scanMillis] collecting matches,
 * then stops. Returns "not nearby" (all nulls) when Bluetooth is off, the permission is missing, or
 * nothing matched, so the caller never has to distinguish those from a real absence.
 *
 * The filter pins the match to the manufacturer id *and* the exact token bytes, so an unrelated
 * 0xFFFF beacon elsewhere can't register as the office.
 */
@SuppressLint("MissingPermission") // guarded by hasBleScanPermission() below
suspend fun detectCompanyBeacon(
    context: Context,
    beaconToken: String,
    scanMillis: Long = DEFAULT_BEACON_SCAN_MILLIS
): CompanyBeaconDetection {
    val notNearby = CompanyBeaconDetection(nearby = false, observedAt = null, lastObservedAt = null)
    if (beaconToken.isBlank() || !hasBleScanPermission(context)) return notNearby
    val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return notNearby
    if (!adapter.isEnabled) return notNearby
    val scanner = adapter.bluetoothLeScanner ?: return notNearby

    val tokenBytes = beaconToken.toByteArray(Charsets.UTF_8)
    val filter = ScanFilter.Builder()
        .setManufacturerData(BEACON_COMPANY_ID, tokenBytes)
        .build()
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val stamps = Collections.synchronizedList(mutableListOf<Long>())
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stamps.add(result.wallClockSeenAt() ?: System.currentTimeMillis())
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { stamps.add(it.wallClockSeenAt() ?: System.currentTimeMillis()) }
        }
    }

    try {
        scanner.startScan(listOf(filter), settings, callback)
        delay(scanMillis)
    } catch (e: Exception) {
        // SecurityException (permission revoked mid-flight) or IllegalStateException (adapter
        // toggled off) — treat as "couldn't see anything" rather than taking the poll down.
        return notNearby
    } finally {
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            // Adapter may have turned off during the window; nothing left to stop.
        }
    }

    val seen = ArrayList(stamps)
    if (seen.isEmpty()) return notNearby
    return CompanyBeaconDetection(nearby = true, observedAt = seen.minOrNull(), lastObservedAt = seen.maxOrNull())
}

/**
 * Scans briefly for *any* office-format beacon (manufacturer id [BEACON_COMPANY_ID]) and returns the
 * distinct payload tokens found, strongest signal first — the BLE analogue of
 * [com.commute.app.wifi.nearbyWifiSsids], so the user can pick a beacon to register without typing
 * its token. Empty when Bluetooth is off, the permission is missing, or nothing is broadcasting.
 */
@SuppressLint("MissingPermission") // guarded by hasBleScanPermission() below
suspend fun scanNearbyBeacons(
    context: Context,
    scanMillis: Long = SEARCH_BEACON_SCAN_MILLIS
): List<String> {
    if (!hasBleScanPermission(context)) return emptyList()
    val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    val scanner = adapter.bluetoothLeScanner ?: return emptyList()

    // Empty data array = "any manufacturer data under this company id", so every office-format
    // beacon shows up regardless of its token.
    val filter = ScanFilter.Builder()
        .setManufacturerData(BEACON_COMPANY_ID, ByteArray(0))
        .build()
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // token -> strongest RSSI seen, so the list can be ordered by proximity.
    val strongestByToken = Collections.synchronizedMap(mutableMapOf<String, Int>())
    fun record(result: ScanResult) {
        val token = result.scanRecord?.getManufacturerSpecificData(BEACON_COMPANY_ID)?.decodeBeaconToken() ?: return
        val prev = strongestByToken[token]
        if (prev == null || result.rssi > prev) strongestByToken[token] = result.rssi
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
    }

    try {
        scanner.startScan(listOf(filter), settings, callback)
        delay(scanMillis)
    } catch (e: Exception) {
        return emptyList()
    } finally {
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
        }
    }

    return synchronized(strongestByToken) { strongestByToken.toList() }
        .sortedByDescending { it.second }
        .map { it.first }
}

/**
 * Decodes a manufacturer-data payload into a display token, or null if it isn't printable text.
 * The office beacon writes an ASCII token (e.g. "COMMUTE1"); anything with control/non-ASCII bytes
 * is some other device's binary payload that happens to share the 0xFFFF id, and showing it as a
 * pickable "beacon" would only confuse the registration list, so it's dropped.
 */
private fun ByteArray.decodeBeaconToken(): String? {
    if (isEmpty()) return null
    if (any { it.toInt() and 0xFF < 0x20 || it.toInt() and 0xFF > 0x7E }) return null
    return String(this, Charsets.US_ASCII).trim().ifBlank { null }
}

/**
 * Converts a [ScanResult.getTimestampNanos] — nanoseconds on the elapsed-realtime clock, not a
 * wall-clock value — into wall-clock millis, anchoring both clocks in the same breath. Mirrors the
 * Wi-Fi scan-timestamp conversion. Returns null for a stamp that can't be true (non-positive, or
 * claiming a sighting from after the current uptime), so a bad driver value falls back to "now"
 * instead of silently backdating.
 */
private fun ScanResult.wallClockSeenAt(): Long? {
    val elapsedNowMs = SystemClock.elapsedRealtime()
    val seenElapsedMs = timestampNanos / 1_000_000
    if (seenElapsedMs <= 0 || seenElapsedMs > elapsedNowMs) return null
    return System.currentTimeMillis() - (elapsedNowMs - seenElapsedMs)
}
