package com.commute.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

private const val POLICY_ASSET_NAME = "가산연구소_운영방안_20220923.pdf"

/** Copies the bundled 근무 규칙 근거 문서(PDF) to cache on first use and opens it in a viewer app. */
fun openPolicyDocument(context: Context) {
    val docsDir = File(context.cacheDir, "docs").apply { mkdirs() }
    val outFile = File(docsDir, POLICY_ASSET_NAME)
    if (!outFile.exists()) {
        context.assets.open(POLICY_ASSET_NAME).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "PDF를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    }
}
