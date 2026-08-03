package com.commute.app.update

import android.content.Context
import java.io.File

/**
 * The inert half of the self-update feature: types, version comparison, and the state machine the
 * settings UI walks through. Everything that actually *acts* — reaching GitHub, downloading an APK,
 * launching the system installer — lives in a per-flavor `UpdateActions.kt`, because it may only
 * exist in the build distributed outside Play.
 *
 * Google Play's Device and Network Abuse policy forbids an app distributed through Play from
 * updating or replacing itself by any route other than Play. This app is also published as a plain
 * APK on GitHub Releases, where a built-in updater is the only sane way to ship a new version — so
 * the capability is real, it just must not be present in the Play build. The `github` flavor
 * supplies a working implementation; the `play` flavor supplies stubs and drops
 * `REQUEST_INSTALL_PACKAGES` from its manifest, so that build has no means of installing anything.
 *
 * These declarations stay in `main` because they're shared and harmless: no network, no installer,
 * and [isNewerVersion] has unit tests that should run against either flavor.
 */

/** One published release: the version tag, the direct APK download link, and its size (for a
 * determinate progress bar; 0 if the server didn't report a length). */
data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/** Thrown for anything an update check can't recover from on its own (network failure, bad
 * response, malformed JSON) — callers show [message] directly, so it's written to be readable. */
class UpdateCheckException(message: String) : Exception(message)

/**
 * Whether [remoteTag] (a GitHub release tag, e.g. "v0.3.0" or "0.3.0") is newer than
 * [localVersionName] (the running app's `versionName`, e.g. "0.2.0") — compared component-by-
 * component as integers so "0.10.0" correctly beats "0.9.0" (plain string comparison wouldn't).
 *
 * A leading "v"/"V" on the tag is stripped first, since that's the conventional release-tag style
 * this project's tags may or may not follow. Missing trailing components compare as 0 (`"1.2"` ==
 * `"1.2.0"`), and a non-numeric component is treated as 0 rather than throwing — a malformed tag
 * should read as "not newer", not crash the update check.
 */
fun isNewerVersion(remoteTag: String, localVersionName: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split(".")
        .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    val remote = parts(remoteTag)
    val local = parts(localVersionName)
    for (i in 0 until maxOf(remote.size, local.size)) {
        val r = remote.getOrElse(i) { 0 }
        val l = local.getOrElse(i) { 0 }
        if (r != l) return r > l
    }
    return false
}

/** The running app's own `versionName` (e.g. "0.2.0"), for display and for [isNewerVersion] to
 * compare a fetched release against. Falls back to "0" on the (practically unreachable) case
 * where the platform can't report it, so a display string always has something to show. */
fun currentAppVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

/** Drives the 앱 업데이트 card end to end: check → (nothing found | found) → download → ready to
 * launch the installer. A linear state machine rather than separate booleans/nullables, so the UI
 * can exhaustively `when` over it instead of reasoning about which combinations are reachable. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateStatus
    data class ReadyToInstall(val apkFile: File) : UpdateStatus
    data class Failed(val message: String?) : UpdateStatus
}
