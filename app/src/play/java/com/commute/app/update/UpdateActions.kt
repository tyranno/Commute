package com.commute.app.update

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * The `play` flavor's stand-in for the self-updater. Google Play's Device and Network Abuse policy
 * forbids an app distributed through Play from updating or replacing itself by any route other than
 * Play, so this build has no updater: the settings screen hides the 앱 업데이트 card on
 * [UPDATER_ENABLED], and the manifest for this flavor omits `REQUEST_INSTALL_PACKAGES` and the
 * `FileProvider` the installer hand-off needs. Updates arrive through Play instead.
 *
 * These functions exist only so the shared UI and ViewModel keep compiling against one set of
 * names. Nothing calls them — every call site is behind [UPDATER_ENABLED] — so they throw rather
 * than pretending to work, which would hide a mistake instead of surfacing it. There is no
 * download or install logic here to accidentally reach.
 */

/** False: this build cannot check for or install its own updates. See the `github` flavor for the
 * implementation used by the APK published on GitHub Releases. */
const val UPDATER_ENABLED = false

private fun disabled(): Nothing =
    throw UnsupportedOperationException("Self-update is not available in the Play build")

fun fetchLatestRelease(): ReleaseInfo? = disabled()

fun downloadApk(cacheDir: File, url: String, onProgress: (Int) -> Unit): File = disabled()

fun canRequestInstallPackages(context: Context): Boolean = false

fun unknownSourcesSettingsIntent(context: Context): Intent = disabled()

fun installApkIntent(context: Context, apkFile: File): Intent = disabled()
