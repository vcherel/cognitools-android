package com.example.myapp.gallery

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DragHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * One pen stroke. Points are fractions of the image and the width a fraction of its long side, so a
 * stroke follows a rotation and lands in the same place on the full resolution save.
 */
private class PenStroke(val points: List<Offset>, val color: Color, val width: Float)

private val penColors = listOf(
    Color(0xFFE53935), Color(0xFFFFD600), Color(0xFF43A047), Color(0xFF1E88E5), Color.Black, Color.White
)
private val penWidths = listOf(0.004f, 0.01f, 0.022f)

private val fullFrame = Rect(0f, 0f, 1f, 1f)

@Composable
fun GalleryCropScreen(itemId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = LocalMediaConsent.current

    var item by remember { mutableStateOf<MediaItem?>(null) }
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // The crop as fractions of the image, so it doesn't care how big the image is drawn.
    var crop by remember { mutableStateOf(fullFrame) }
    var rotation by remember { mutableIntStateOf(0) }
    val strokes = remember { mutableStateListOf<PenStroke>() }
    var drawing by remember { mutableStateOf(false) }
    var penColor by remember { mutableStateOf(penColors.first()) }
    var penWidth by remember { mutableFloatStateOf(penWidths[1]) }
    var saving by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        val found = withContext(Dispatchers.IO) { queryMediaItemById(context, itemId) }
        item = found
        if (found != null) {
            val decoded = withContext(Dispatchers.IO) { decodeSampledBitmap(context, found.uri, maxDimension = 1440) }
            if (decoded != null) displayBitmap = decoded else errorMessage = "Image illisible"
        } else {
            errorMessage = "Image introuvable"
        }
    }

    BackHandler { onBack() }
    ShowSystemBars()

    val currentItem = item
    val bitmap = displayBitmap

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Modifier", onBack = onBack, modifier = Modifier.padding(16.dp), titleWeight = true) {
            if (bitmap != null && !saving) {
                if (strokes.isNotEmpty()) {
                    IconButton(onClick = { strokes.removeAt(strokes.lastIndex) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Annuler le trait")
                    }
                }
                FilledIconToggleButton(checked = drawing, onCheckedChange = { drawing = it }) {
                    Icon(Icons.Default.Brush, contentDescription = if (drawing) "Recadrer" else "Dessiner")
                }
                IconButton(enabled = !rotating, onClick = {
                    rotating = true
                    scope.launch {
                        displayBitmap = withContext(Dispatchers.Default) { bitmap.rotated(90) }
                        rotation = (rotation + 90) % 360
                        crop = fullFrame
                        val turned = strokes.map { s -> PenStroke(s.points.map { Offset(1f - it.y, it.x) }, s.color, s.width) }
                        strokes.clear()
                        strokes.addAll(turned)
                        rotating = false
                    }
                }) {
                    Icon(Icons.Default.Rotate90DegreesCw, contentDescription = "Pivoter")
                }
            }
        }

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
                // Leaves room around the image so a crop handle dragged to an image edge still has
                // touchable Canvas space around it, instead of sitting flush on the box boundary
                // where an imprecise touch falls through to whatever is laid out below/beside it.
                val handleMarginPx = with(LocalDensity.current) { 12.dp.toPx() }
                val imageRect = remember(bitmap, boxWidthPx, boxHeightPx) {
                    val availWidthPx = (boxWidthPx - handleMarginPx * 2).coerceAtLeast(1f)
                    val availHeightPx = (boxHeightPx - handleMarginPx * 2).coerceAtLeast(1f)
                    val fitScale = minOf(availWidthPx / bitmap.width, availHeightPx / bitmap.height)
                    val width = bitmap.width * fitScale
                    val height = bitmap.height * fitScale
                    val left = (boxWidthPx - width) / 2f
                    val top = (boxHeightPx - height) / 2f
                    Rect(left, top, left + width, top + height)
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = currentItem.displayName,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.layout { measurable, _ ->
                        val placeable = measurable.measure(
                            Constraints.fixed(imageRect.width.toInt(), imageRect.height.toInt())
                        )
                        layout(boxWidthPx.toInt(), boxHeightPx.toInt()) {
                            placeable.place(imageRect.left.toInt(), imageRect.top.toInt())
                        }
                    }
                )
                EditorOverlay(
                    imageRect = imageRect,
                    crop = crop,
                    onCropChange = { crop = it },
                    strokes = strokes,
                    drawing = drawing,
                    penColor = penColor,
                    penWidth = penWidth,
                    onStroke = { strokes.add(it) }
                )
            }

            if (drawing) {
                PenTools(
                    color = penColor,
                    onColor = { penColor = it },
                    width = penWidth,
                    onWidth = { penWidth = it }
                )
            }

            MyButton(
                text = if (saving) "Enregistrement..." else "Enregistrer",
                enabled = !saving && !rotating,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(64.dp)
            ) {
                if (rotation == 0 && crop == fullFrame && strokes.isEmpty()) {
                    onBack()
                    return@MyButton
                }
                val ink = strokes.toList()
                scope.launch {
                    saving = true
                    val edited = withContext(Dispatchers.Default) {
                        editToFullResolution(context, currentItem, rotation, crop, ink)
                    }
                    if (edited == null) {
                        saving = false
                        errorMessage = "Modification impossible"
                        return@launch
                    }
                    val ok = performOverwrite(context, currentItem, requestConsent) { out ->
                        val format = if (currentItem.mimeType.contains("png", ignoreCase = true)) {
                            Bitmap.CompressFormat.PNG
                        } else {
                            Bitmap.CompressFormat.JPEG
                        }
                        edited.compress(format, 92, out)
                    }
                    edited.recycle()
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
private fun PenTools(color: Color, onColor: (Color) -> Unit, width: Float, onWidth: (Float) -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    val selected = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        penColors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onColor(c) }
                    .padding(if (c == color) 2.dp else 6.dp)
                    .border(if (c == color) 3.dp else 1.dp, if (c == color) selected else ring.copy(alpha = 0.4f), CircleShape)
                    .padding(if (c == color) 4.dp else 1.dp)
                    .background(c, CircleShape)
            )
        }
        Spacer(Modifier.width(4.dp))
        penWidths.forEachIndexed { i, w ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (w == width) selected.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onWidth(w) },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size((6 + 6 * i).dp).background(color = ring, shape = CircleShape))
            }
        }
    }
}

/**
 * Everything drawn over the image: the pen strokes, and the crop frame with the scrim around it.
 * Drags draw with the pen while [drawing], move the crop frame otherwise.
 */
@Composable
private fun EditorOverlay(
    imageRect: Rect,
    crop: Rect,
    onCropChange: (Rect) -> Unit,
    strokes: List<PenStroke>,
    drawing: Boolean,
    penColor: Color,
    penWidth: Float,
    onStroke: (PenStroke) -> Unit
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 24.dp.toPx() }
    val currentImageRect by rememberUpdatedState(imageRect)
    val currentCrop by rememberUpdatedState(crop)
    val currentColor by rememberUpdatedState(penColor)
    val currentWidth by rememberUpdatedState(penWidth)
    val inProgress = remember { mutableStateListOf<Offset>() }

    fun Rect.toPx(r: Rect) = Rect(r.left + left * r.width, r.top + top * r.height, r.left + right * r.width, r.top + bottom * r.height)
    fun Rect.toFrac(r: Rect) = Rect((left - r.left) / r.width, (top - r.top) / r.height, (right - r.left) / r.width, (bottom - r.top) / r.height)

    val input = if (drawing) {
        Modifier.pointerInput(Unit) {
            fun frac(p: Offset): Offset {
                val r = currentImageRect
                return Offset((p.x - r.left) / r.width, (p.y - r.top) / r.height)
            }
            detectDragGestures(
                onDragStart = { inProgress.clear(); inProgress.add(frac(it)) },
                onDragEnd = {
                    onStroke(PenStroke(inProgress.toList(), currentColor, currentWidth))
                    inProgress.clear()
                },
                onDragCancel = { inProgress.clear() }
            ) { change, _ ->
                change.consume()
                inProgress.add(frac(change.position))
            }
        }
    } else {
        Modifier.pointerInputDragCrop(
            handleRadiusPx,
            { currentCrop.toPx(currentImageRect) },
            { currentImageRect },
            { onCropChange(it.toFrac(currentImageRect)) }
        )
    }

    Canvas(modifier = Modifier.fillMaxSize().then(input)) {
        val longSide = maxOf(imageRect.width, imageRect.height)
        clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
            fun ink(points: List<Offset>, color: Color, width: Float) {
                val px = points.map { Offset(imageRect.left + it.x * imageRect.width, imageRect.top + it.y * imageRect.height) }
                val strokePx = width * longSide
                if (px.size == 1) {
                    drawCircle(color, radius = strokePx / 2, center = px[0])
                    return
                }
                val path = Path().apply {
                    moveTo(px[0].x, px[0].y)
                    for (i in 1 until px.size) lineTo(px[i].x, px[i].y)
                }
                drawPath(path, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            strokes.forEach { ink(it.points, it.color, it.width) }
            if (inProgress.isNotEmpty()) ink(inProgress, currentColor, currentWidth)
        }

        val cropRect = crop.toPx(imageRect)
        val scrim = Color.White.copy(alpha = 0.55f)
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, cropRect.top))
        drawRect(scrim, topLeft = Offset(0f, cropRect.bottom), size = Size(size.width, size.height - cropRect.bottom))
        drawRect(scrim, topLeft = Offset(0f, cropRect.top), size = Size(cropRect.left, cropRect.height))
        drawRect(
            scrim,
            topLeft = Offset(cropRect.right, cropRect.top),
            size = Size((size.width - cropRect.right).coerceAtLeast(0f), cropRect.height)
        )
        if (drawing) return@Canvas
        drawRect(Color.Black, topLeft = cropRect.topLeft, size = cropRect.size, style = Stroke(width = 2.dp.toPx()))
        val handleSize = 8.dp.toPx()
        listOf(cropRect.topLeft, Offset(cropRect.right, cropRect.top), Offset(cropRect.left, cropRect.bottom), cropRect.bottomRight)
            .forEach { corner ->
                drawRect(
                    Color.Black,
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

// ImageDecoder applies the EXIF orientation, so the pixels come out the way the photo is seen: the
// saved file carries no EXIF, and a sideways camera shot would otherwise be written sideways.
private fun decodeSampledBitmap(context: android.content.Context, uri: android.net.Uri, maxDimension: Int): Bitmap? =
    try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            var sample = 1
            val longSide = maxOf(info.size.width, info.size.height)
            while (longSide / (sample * 2) >= maxDimension) {
                sample *= 2
            }
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    return Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}

// Re-decodes the source at (near) full resolution, rotates it, inks the strokes, and applies the
// crop. Crop and strokes are fractions of the image, so they map straight onto the bigger bitmap.
private fun editToFullResolution(
    context: android.content.Context,
    item: MediaItem,
    rotation: Int,
    crop: Rect,
    strokes: List<PenStroke>
): Bitmap? {
    val decoded = decodeSampledBitmap(context, item.uri, maxDimension = 4096) ?: return null
    val full = decoded.rotated(rotation)
    if (full !== decoded) decoded.recycle()
    val w = full.width
    val h = full.height

    val left = (crop.left.coerceIn(0f, 1f) * w).toInt().coerceIn(0, w - 1)
    val top = (crop.top.coerceIn(0f, 1f) * h).toInt().coerceIn(0, h - 1)
    val right = (crop.right.coerceIn(0f, 1f) * w).toInt().coerceIn(left + 1, w)
    val bottom = (crop.bottom.coerceIn(0f, 1f) * h).toInt().coerceIn(top + 1, h)

    val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
    if (cropped !== full) full.recycle()
    if (strokes.isEmpty()) return cropped

    val inked = cropped.copy(Bitmap.Config.ARGB_8888, true)
    cropped.recycle()
    val canvas = android.graphics.Canvas(inked)
    canvas.translate(-left.toFloat(), -top.toFloat())
    val longSide = maxOf(w, h).toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        paint.color = stroke.color.toArgb()
        paint.strokeWidth = stroke.width * longSide
        val path = android.graphics.Path()
        stroke.points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h)
        }
        if (stroke.points.size == 1) path.lineTo(stroke.points[0].x * w + 0.1f, stroke.points[0].y * h)
        canvas.drawPath(path, paint)
    }
    return inked
}
