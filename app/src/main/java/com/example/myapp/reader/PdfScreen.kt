package com.example.myapp.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapp.ErrorText
import com.example.myapp.ScreenTopBar
import com.example.myapp.SuppressIdleReset
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.runIgnoringErrors
import com.example.myapp.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2f

/** Past this, a page is stretched rather than drawn bigger: a 3x page is already 26 MB on this screen. */
private const val MAX_RENDER_LEVEL = 3

private val CHROME_CLEARANCE = 72.dp

fun pdfFileRoute(uri: Uri): String = "reader/file/${Uri.encode(uri.toString())}"

/** A PDF that lives elsewhere, from the file explorer or another app. Not filed, so no position is kept. */
@Composable
fun PdfFileScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val title by produceState("", uri) {
        value = withContext(Dispatchers.IO) { documentTitle(context, uri) }
    }
    PdfViewer(uri = uri, title = title, onBack = onBack)
}

/** A PDF of the Reader's library, reopened where it was left. */
@Composable
fun PdfBookScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).bookDao() }
    val book by produceState<Book?>(null, bookId) { value = dao.getBook(bookId) }

    book?.let { loaded ->
        val uri = remember(loaded.id) { Uri.fromFile(bookFile(context, loaded)) }
        PdfViewer(
            uri = uri,
            title = loaded.title,
            initialPage = loaded.chapterIndex,
            initialOffset = loaded.blockOffset,
            onBack = onBack,
            onPosition = { page, offset ->
                dao.saveProgress(bookId, page, 0, offset, System.currentTimeMillis())
            }
        )
    }
}

/**
 * Every page in one continuous scroll. Pinching resizes the pages themselves rather than the whole
 * list, so the scroll keeps its real bounds; a page is drawn again sharper once the fingers lift.
 */
@Composable
private fun PdfViewer(
    uri: Uri,
    title: String,
    onBack: () -> Unit,
    initialPage: Int = 0,
    initialOffset: Int = 0,
    onPosition: (suspend (page: Int, offset: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val listState = rememberLazyListState(initialPage, initialOffset)
    var document by remember(uri) { mutableStateOf<PdfDocument?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var showChrome by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var renderLevel by remember { mutableIntStateOf(1) }

    SuppressIdleReset()

    LaunchedEffect(uri) {
        val opened = runCatching { openPdf(context, uri) }.getOrElse {
            error = userMessage(it, "Can't open this file")
            return@LaunchedEffect
        }
        document = opened
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { opened.close() }
        }
    }

    if (onPosition != null) {
        LaunchedEffect(Unit) {
            // Stored as it would be at 1x, since the document always reopens at 1x.
            snapshotFlow { listState.firstVisibleItemIndex to (listState.firstVisibleItemScrollOffset / scale).toInt() }
                .collectLatest { (page, offset) ->
                    delay(PROGRESS_SAVE_DELAY_MS)
                    onPosition(page, offset)
                }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val current = document
        val density = LocalDensity.current
        val viewportWidth = constraints.maxWidth.toFloat()
        val topPadding = with(density) { CHROME_CLEARANCE.toPx() }

        fun clampPan(value: Float): Float {
            val limit = viewportWidth * (scale - 1f) / 2f
            return value.coerceIn(-limit, limit)
        }

        // Keeps the point under the fingers where it is. Anchored on the first visible page, whose
        // new offset is the same distance to the fingers scaled by the same ratio.
        fun zoomAround(focus: Offset, targetScale: Float) {
            val newScale = targetScale.coerceIn(1f, MAX_ZOOM)
            val ratio = newScale / scale
            if (ratio == 1f) return
            val focusInList = focus.y - topPadding
            val distance = focusInList + listState.firstVisibleItemScrollOffset
            listState.requestScrollToItem(
                listState.firstVisibleItemIndex,
                (distance * ratio - focusInList).roundToInt().coerceAtLeast(0)
            )
            val pan = panX * ratio + (focus.x - viewportWidth / 2f) * (1f - ratio)
            scale = newScale
            panX = clampPan(pan)
        }

        fun settleRenderLevel() {
            renderLevel = ceil(scale).toInt().coerceIn(1, MAX_RENDER_LEVEL)
        }

        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorText(message = error!!, onDismiss = onBack, modifier = Modifier.padding(24.dp))
            }

            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = CHROME_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(viewportWidth) {
                        // Seen before the list: two fingers are taken from it, one finger pans
                        // sideways while the list still scrolls on the same drag.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    zoomAround(event.calculateCentroid(), scale * event.calculateZoom())
                                    panX = clampPan(panX + event.calculatePan().x)
                                    event.changes.forEach { it.consume() }
                                } else if (scale > 1f) {
                                    panX = clampPan(panX + event.calculatePan().x)
                                }
                            } while (event.changes.any { it.pressed })
                            settleRenderLevel()
                        }
                    }
                    .pointerInput(viewportWidth) {
                        detectTapGestures(
                            onTap = { showChrome = !showChrome },
                            onDoubleTap = { position ->
                                zoomAround(position, if (scale > 1f) 1f else DOUBLE_TAP_ZOOM)
                                settleRenderLevel()
                            }
                        )
                    }
            ) {
                itemsIndexed(current.pageAspects) { index, aspect ->
                    val pageWidth = with(density) { (viewportWidth * scale).toDp() }
                    PdfPage(
                        document = current,
                        index = index,
                        width = pageWidth,
                        height = pageWidth / aspect,
                        renderWidth = (viewportWidth * renderLevel).roundToInt(),
                        panX = { panX }
                    )
                }
            }
        }

        if (showChrome) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                ScreenTopBar(
                    title = title,
                    onBack = onBack,
                    titleStyle = MaterialTheme.typography.titleMedium,
                    titleMaxLines = 1,
                    titleWeight = true
                )
            }

            if (current != null) {
                Text(
                    text = "${listState.firstVisibleItemIndex + 1} / ${current.pageAspects.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PdfPage(
    document: PdfDocument,
    index: Int,
    width: Dp,
    height: Dp,
    renderWidth: Int,
    panX: () -> Float
) {
    // The previous bitmap stays up, stretched, until the sharper one is ready.
    var bitmap by remember(document, index) { mutableStateOf<Bitmap?>(document.cached(index, renderWidth)) }
    LaunchedEffect(document, index, renderWidth) {
        runIgnoringErrors { document.render(index, renderWidth) }.getOrNull()?.let { bitmap = it }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .requiredSize(width, height)
                .graphicsLayer { translationX = panX() }
                .background(Color.White)
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
