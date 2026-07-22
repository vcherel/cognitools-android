package com.example.myapp.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DragHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

@Composable
fun GalleryCropScreen(itemId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = rememberIntentSenderRequester()

    var item by remember { mutableStateOf<MediaItem?>(null) }
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cropRect by remember { mutableStateOf<Rect?>(null) }
    var imageRect by remember { mutableStateOf(Rect.Zero) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        val found = withContext(Dispatchers.IO) { queryMediaItemById(context, itemId) }
        item = found
        if (found != null) {
            displayBitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(context, found.uri, maxDimension = 1440) }
        }
    }

    BackHandler { onBack() }

    val currentItem = item
    val bitmap = displayBitmap

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Rogner", onBack = onBack, modifier = Modifier.padding(16.dp))

        if (currentItem == null || bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                val boxWidthPx = constraints.maxWidth.toFloat()
                val boxHeightPx = constraints.maxHeight.toFloat()
                val fitScale = minOf(boxWidthPx / bitmap.width, boxHeightPx / bitmap.height)
                val displayWidth = bitmap.width * fitScale
                val displayHeight = bitmap.height * fitScale
                val computedImageRect = remember(bitmap, boxWidthPx, boxHeightPx) {
                    val left = (boxWidthPx - displayWidth) / 2f
                    val top = (boxHeightPx - displayHeight) / 2f
                    Rect(left, top, left + displayWidth, top + displayHeight)
                }
                SideEffect { imageRect = computedImageRect }
                if (cropRect == null) {
                    cropRect = computedImageRect.deflate(computedImageRect.width * 0.1f)
                }
                val rect = cropRect
                if (rect != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = currentItem.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    CropOverlay(
                        imageRect = computedImageRect,
                        cropRect = rect,
                        onCropRectChange = { cropRect = it }
                    )
                }
            }

            MyButton(
                text = if (saving) "Enregistrement..." else "Enregistrer",
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(64.dp)
            ) {
                val rect = cropRect ?: return@MyButton
                scope.launch {
                    saving = true
                    val cropped = withContext(Dispatchers.Default) {
                        cropToFullResolution(context, currentItem, bitmap, imageRect, rect)
                    }
                    if (cropped == null) {
                        saving = false
                        errorMessage = "Rognage impossible"
                        return@launch
                    }
                    val ok = performOverwrite(context, currentItem, requestConsent) { out ->
                        val format = if (currentItem.mimeType.contains("png", ignoreCase = true)) {
                            Bitmap.CompressFormat.PNG
                        } else {
                            Bitmap.CompressFormat.JPEG
                        }
                        cropped.compress(format, 92, out)
                    }
                    cropped.recycle()
                    saving = false
                    if (ok) onBack() else errorMessage = "Enregistrement impossible"
                }
            }
        }
    }

    errorMessage?.let { message ->
        ShowAlertDialog(
            onDismiss = { errorMessage = null },
            title = message,
            onConfirm = { errorMessage = null }
        )
    }
}

@Composable
private fun CropOverlay(imageRect: Rect, cropRect: Rect, onCropRectChange: (Rect) -> Unit) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 24.dp.toPx() }
    val currentCropRect by rememberUpdatedState(cropRect)
    val currentImageRect by rememberUpdatedState(imageRect)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInputDragCrop(handleRadiusPx, { currentCropRect }, { currentImageRect }, onCropRectChange)
    ) {
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, cropRect.top))
        drawRect(scrim, topLeft = Offset(0f, cropRect.bottom), size = Size(size.width, size.height - cropRect.bottom))
        drawRect(scrim, topLeft = Offset(0f, cropRect.top), size = Size(cropRect.left, cropRect.height))
        drawRect(
            scrim,
            topLeft = Offset(cropRect.right, cropRect.top),
            size = Size((size.width - cropRect.right).coerceAtLeast(0f), cropRect.height)
        )
        drawRect(Color.White, topLeft = cropRect.topLeft, size = cropRect.size, style = Stroke(width = 2.dp.toPx()))
        val handleSize = 8.dp.toPx()
        listOf(cropRect.topLeft, Offset(cropRect.right, cropRect.top), Offset(cropRect.left, cropRect.bottom), cropRect.bottomRight)
            .forEach { corner ->
                drawRect(
                    Color.White,
                    topLeft = Offset(corner.x - handleSize / 2, corner.y - handleSize / 2),
                    size = Size(handleSize, handleSize)
                )
            }
    }
}

private fun Modifier.pointerInputDragCrop(
    handleRadiusPx: Float,
    cropRectProvider: () -> Rect,
    imageRectProvider: () -> Rect,
    onCropRectChange: (Rect) -> Unit
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        var handle = DragHandle.NONE
        detectDragGestures(
            onDragStart = { pos ->
                val r = cropRectProvider()
                handle = when {
                    (pos - r.topLeft).getDistance() < handleRadiusPx -> DragHandle.TOP_LEFT
                    (pos - Offset(r.right, r.top)).getDistance() < handleRadiusPx -> DragHandle.TOP_RIGHT
                    (pos - Offset(r.left, r.bottom)).getDistance() < handleRadiusPx -> DragHandle.BOTTOM_LEFT
                    (pos - r.bottomRight).getDistance() < handleRadiusPx -> DragHandle.BOTTOM_RIGHT
                    r.contains(pos) -> DragHandle.MOVE
                    else -> DragHandle.NONE
                }
            },
            onDragEnd = { handle = DragHandle.NONE }
        ) { change, dragAmount ->
            if (handle == DragHandle.NONE) return@detectDragGestures
            change.consume()
            val imgRect = imageRectProvider()
            val r = cropRectProvider()
            val minSize = 60f
            var left = r.left
            var top = r.top
            var right = r.right
            var bottom = r.bottom
            when (handle) {
                DragHandle.MOVE -> {
                    val dx = dragAmount.x.coerceIn(imgRect.left - left, imgRect.right - right)
                    val dy = dragAmount.y.coerceIn(imgRect.top - top, imgRect.bottom - bottom)
                    left += dx; right += dx; top += dy; bottom += dy
                }
                DragHandle.TOP_LEFT -> {
                    left = (left + dragAmount.x).coerceIn(imgRect.left, right - minSize)
                    top = (top + dragAmount.y).coerceIn(imgRect.top, bottom - minSize)
                }
                DragHandle.TOP_RIGHT -> {
                    right = (right + dragAmount.x).coerceIn(left + minSize, imgRect.right)
                    top = (top + dragAmount.y).coerceIn(imgRect.top, bottom - minSize)
                }
                DragHandle.BOTTOM_LEFT -> {
                    left = (left + dragAmount.x).coerceIn(imgRect.left, right - minSize)
                    bottom = (bottom + dragAmount.y).coerceIn(top + minSize, imgRect.bottom)
                }
                DragHandle.BOTTOM_RIGHT -> {
                    right = (right + dragAmount.x).coerceIn(left + minSize, imgRect.right)
                    bottom = (bottom + dragAmount.y).coerceIn(top + minSize, imgRect.bottom)
                }
                DragHandle.NONE -> {}
            }
            onCropRectChange(Rect(left, top, right, bottom))
        }
    }
)

private fun decodeSampledBitmap(context: android.content.Context, uri: android.net.Uri, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension && bounds.outHeight / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

// Re-decodes the source at (near) full resolution and applies the crop rectangle, which was
// drawn against the smaller, screen-sized `displayBitmap`. imageRect/cropRect are in the crop
// screen's Box pixel coordinates, so they're first normalized against displayBitmap's own bounds.
private fun cropToFullResolution(
    context: android.content.Context,
    item: MediaItem,
    displayBitmap: Bitmap,
    imageRect: Rect,
    cropRect: Rect
): Bitmap? {
    val full = decodeSampledBitmap(context, item.uri, maxDimension = 4096) ?: return null
    val fracLeft = ((cropRect.left - imageRect.left) / imageRect.width).coerceIn(0f, 1f)
    val fracTop = ((cropRect.top - imageRect.top) / imageRect.height).coerceIn(0f, 1f)
    val fracRight = ((cropRect.right - imageRect.left) / imageRect.width).coerceIn(0f, 1f)
    val fracBottom = ((cropRect.bottom - imageRect.top) / imageRect.height).coerceIn(0f, 1f)

    val left = (fracLeft * full.width).toInt().coerceIn(0, full.width - 1)
    val top = (fracTop * full.height).toInt().coerceIn(0, full.height - 1)
    val right = (fracRight * full.width).toInt().coerceIn(left + 1, full.width)
    val bottom = (fracBottom * full.height).toInt().coerceIn(top + 1, full.height)

    val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
    if (cropped !== full) full.recycle()
    return cropped
}
