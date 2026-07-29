package com.commute.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One registered office Wi-Fi network: a name the user recognizes plus the hardware APs (BSSIDs)
 * confirmed to be broadcasting it. Detection treats several registered networks the same way it
 * already treats Wi-Fi and BLE — present if *any one* of them is in range — which covers an
 * office that spans more than one physical network: different floors or buildings, a router that
 * got replaced mid-use and now broadcasts under a different name, or simply more than one AP
 * brand in the same space.
 */
data class CompanyNetwork(val ssid: String, val bssids: Set<String> = emptySet())

/** Serializes [networks] to the compact JSON form stored in DataStore and in backups. */
fun encodeCompanyNetworks(networks: List<CompanyNetwork>): String =
    JSONArray().apply {
        networks.forEach { network ->
            put(
                JSONObject().apply {
                    put("ssid", network.ssid)
                    put("bssids", JSONArray().apply { network.bssids.forEach { put(it) } })
                }
            )
        }
    }.toString()

/** Parses a string written by [encodeCompanyNetworks]. Blank or malformed input reads as "nothing
 * registered" rather than throwing — a corrupt preference value shouldn't crash every poll. */
fun decodeCompanyNetworks(json: String): List<CompanyNetwork> {
    if (json.isBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val bssids = obj.optJSONArray("bssids")?.let { b ->
                (0 until b.length()).mapNotNull { b.optString(it, "").ifBlank { null } }.toSet()
            } ?: emptySet()
            CompanyNetwork(ssid = obj.getString("ssid"), bssids = bssids)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Serializes an ordered string list (registered beacon tokens) to the same compact JSON-array
 * form [encodeCompanyNetworks] uses, so DataStore can carry more than one value under one key
 * while preserving registration order — order matters for picking a fallback representative token
 * when nothing is currently detected. */
fun encodeStringList(values: List<String>): String = JSONArray(values).toString()

/** Parses a string written by [encodeStringList]. Blank or malformed input reads as "nothing
 * registered" rather than throwing — a corrupt preference value shouldn't crash every poll. */
fun decodeStringList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it, "").ifBlank { null } }
    } catch (e: Exception) {
        emptyList()
    }
}
