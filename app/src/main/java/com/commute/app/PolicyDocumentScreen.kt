package com.commute.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val POLICY_ASSET_NAME = "가산연구소_운영방안_20220923.pdf"

/** In-app view of the 가산 연구소 운영 방안 document — renders the bundled PDF's page directly
 * to a bitmap with the platform's [PdfRenderer] and shows it as an image, so there's no need to
 * hand off to an external PDF viewer app for it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyDocumentScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        pageBitmap = withContext(Dispatchers.IO) { renderPolicyDocumentPage(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("가산 연구소 운영 방안") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { innerPadding ->
        val bitmap = pageBitmap
        if (bitmap == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "가산 연구소 운영 방안 문서",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        .padding(8.dp)
                )
            }
        }
    }
}

/** Copies the bundled PDF to cache on first use (PdfRenderer needs a real file descriptor, not
 * an asset stream) and rasterizes its first page at a multiple of its native resolution so text
 * stays crisp when the image is displayed at full device width. */
private fun renderPolicyDocumentPage(context: Context): Bitmap {
    val cacheFile = File(context.cacheDir, POLICY_ASSET_NAME)
    if (!cacheFile.exists()) {
        context.assets.open(POLICY_ASSET_NAME).use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
    ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(0).use { page ->
                val scale = 3
                val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}
