package com.commute.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private const val POLICY_ASSET_NAME_KO = "가산연구소_운영방안_20220923.pdf"
/** A separately translated document, not a machine translation generated on the fly — the 가산
 * 연구소 운영 방안 is short and fixed (dated 2022.09.23), so a one-time human-quality translation
 * bundled as its own PDF is simpler and more trustworthy than translating extracted text at
 * runtime, and keeps the same page-image rendering path for both languages. */
private const val POLICY_ASSET_NAME_EN = "가산연구소_운영방안_20220923_EN.pdf"

/** In-app view of the 가산 연구소 운영 방안 document — renders the bundled PDF's page directly
 * to a bitmap with the platform's [PdfRenderer] and shows it as an image, so there's no need to
 * hand off to an external PDF viewer app for it. [lang] picks which language's PDF is shown,
 * matching the rest of the UI's language choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyDocumentScreen(lang: Lang = Lang.KO, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(lang) {
        pageBitmap = null
        val assetName = if (lang == Lang.EN) POLICY_ASSET_NAME_EN else POLICY_ASSET_NAME_KO
        pageBitmap = withContext(Dispatchers.IO) { renderPolicyDocumentPage(context, assetName) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.policyTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
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
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var viewportSize by remember { mutableStateOf(Size.Zero) }
            var contentSize by remember { mutableStateOf(Size.Zero) }

            // Recomputed on every change rather than clamped only at gesture time, so shrinking the
            // scale back toward 1x (pinch-in, or the double-tap reset) pulls a pan that used to be
            // in-bounds at a higher zoom back within the now-smaller allowed range too.
            fun clamp(target: Offset, atScale: Float): Offset {
                val maxX = max(0f, (contentSize.width * atScale - viewportSize.width) / 2f)
                val maxY = max(0f, (contentSize.height * atScale - viewportSize.height) / 2f)
                return Offset(target.x.coerceIn(-maxX, maxX), target.y.coerceIn(-maxY, maxY))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .onSizeChanged { viewportSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    // Pinch to zoom in/out (min 1x — never smaller than fit-to-width — max 5x) and
                    // drag to pan, the same two-finger/one-finger combo any photo or map app uses.
                    // detectTransformGestures reports pan from a single pointer too, so dragging at
                    // 1x scrolls through the page exactly like the old verticalScroll did.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            offset = clamp(offset * (newScale / scale) + pan, newScale)
                            scale = newScale
                        }
                    }
                    // A quick way back out once zoomed in — pinching precisely back to exactly 1x
                    // is fiddly, so double-tap snaps to it (a no-op, and harmless, at rest).
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = s.policyDocDesc,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        .padding(8.dp)
                        .onSizeChanged { contentSize = Size(it.width.toFloat(), it.height.toFloat()) }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }
    }
}

/** Copies the bundled PDF to cache on first use (PdfRenderer needs a real file descriptor, not
 * an asset stream) and rasterizes its first page at a multiple of its native resolution so text
 * stays crisp when the image is displayed at full device width. Cached under [assetName] so the
 * Korean and English PDFs don't collide with (or shadow) each other's cached file. */
private fun renderPolicyDocumentPage(context: Context, assetName: String): Bitmap {
    val cacheFile = File(context.cacheDir, assetName)
    if (!cacheFile.exists()) {
        context.assets.open(assetName).use { input ->
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
