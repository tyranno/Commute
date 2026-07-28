package com.commute.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Where release APKs are published — this app isn't distributed through Play, so updates are
 * checked against the latest GitHub Release on the user's own repo instead. Change these two if
 * the repo ever moves. */
private const val UPDATE_REPO_OWNER = "tyranno"
private const val UPDATE_REPO_NAME = "Commute"

/** One published release: the version tag, the direct APK download link, and its size (for a
 * determinate progress bar; 0 if the server didn't report a length). */
data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/** Thrown for anything [fetchLatestRelease] can't recover from on its own (network failure, bad
 * response, malformed JSON) — callers show [message] directly, so it's written to be readable. */
class UpdateCheckException(message: String) : Exception(message)

/**
 * Fetches the newest published release from [UPDATE_REPO_OWNER]/[UPDATE_REPO_NAME] and returns it,
 * or null if the repo has no releases yet (a fresh repo, or before the first one is published —
 * not an error, just nothing to offer). GitHub's `/releases/latest` already excludes drafts and
 * pre-releases, so anything it returns is meant to be installed.
 *
 * Picks the first release asset whose name ends in ".apk" — this project only ever attaches one.
 * A release with no APK attached (tagged but nothing uploaded yet) is treated the same as "no
 * release", since there'd be nothing to download anyway.
 *
 * Blocking (plain [HttpURLConnection]) like the rest of this app's file I/O — callers wrap it in
 * `withContext(Dispatchers.IO)`, matching [com.commute.app.data.buildBackupJson]'s call sites.
 */
fun fetchLatestRelease(): ReleaseInfo? {
    val url = URL("https://api.github.com/repos/$UPDATE_REPO_OWNER/$UPDATE_REPO_NAME/releases/latest")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        when (connection.responseCode) {
            HttpURLConnection.HTTP_OK -> Unit
            HttpURLConnection.HTTP_NOT_FOUND -> return null
            else -> throw UpdateCheckException("HTTP ${connection.responseCode}")
        }
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(body)
        val tag = root.optString("tag_name").ifBlank { return null }
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return ReleaseInfo(
                    version = tag,
                    downloadUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size", 0L)
                )
            }
        }
        return null
    } catch (e: UpdateCheckException) {
        throw e
    } catch (e: Exception) {
        throw UpdateCheckException(e.message ?: e.javaClass.simpleName)
    } finally {
        connection.disconnect()
    }
}

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

/**
 * Downloads [url] into `cacheDir/updates/update.apk`, replacing any previous download, and reports
 * progress as a 0..100 percentage via [onProgress] (called on the calling thread — callers wrap
 * this whole function in `withContext(Dispatchers.IO)`, so that's the IO dispatcher, not Main).
 * Percentage is only as precise as the server's `Content-Length`; when that's missing/zero,
 * [onProgress] is simply never called and the caller shows an indeterminate state instead.
 */
fun downloadApk(cacheDir: File, url: String, onProgress: (Int) -> Unit): File {
    val dir = File(cacheDir, "updates").apply { mkdirs() }
    val outFile = File(dir, "update.apk")
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw UpdateCheckException("HTTP ${connection.responseCode}")
        }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            outFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var readSoFar = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    readSoFar += n
                    if (total > 0) onProgress(((readSoFar * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        return outFile
    } catch (e: UpdateCheckException) {
        throw e
    } catch (e: Exception) {
        throw UpdateCheckException(e.message ?: e.javaClass.simpleName)
    } finally {
        connection.disconnect()
    }
}

/** The running app's own `versionName` (e.g. "0.2.0"), for display and for [isNewerVersion] to
 * compare a fetched release against. Falls back to "0" on the (practically unreachable) case
 * where the platform can't report it, so a display string always has something to show. */
fun currentAppVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

/** Whether this app can launch the package installer without the user first flipping on "install
 * unknown apps" for it in system Settings. Always true before Android 8 (Oreo), which didn't gate
 * this per-app — installs were controlled by one device-wide toggle the app can't query. */
fun canRequestInstallPackages(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

/** Deep-links into the system Settings screen for granting this app "install unknown apps". */
fun unknownSourcesSettingsIntent(context: Context): Intent =
    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

/** An install intent for [apkFile], handed a `content://` Uri via [FileProvider] since Android 7+
 * rejects a bare `file://` Uri shared across apps (the system installer runs as a different app). */
fun installApkIntent(context: Context, apkFile: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

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
