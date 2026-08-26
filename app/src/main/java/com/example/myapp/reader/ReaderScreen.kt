package com.example.myapp.reader

import com.example.myapp.userMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.ErrorText
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.SuppressIdleReset
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.translate.TranslateLang
import com.example.myapp.translate.TranslateStore
import com.example.myapp.translate.WordLookupSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long the scrolling has to settle before the position is written back. */
private const val PROGRESS_SAVE_DELAY_MS = 500L

/**
 * One chapter scrolls continuously; the position is written back as soon as the scrolling settles,
 * so the book reopens where it was even if the process is killed with the screen off. Tapping the
 * page shows the controls, long pressing a word looks it up in the translator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).bookDao() }
    val listState = rememberLazyListState()
    val fontSize by ReaderPrefs.fontSize(context).collectAsState(initial = ReaderPrefs.DEFAULT_FONT_SIZE)
    val lookupTarget by TranslateStore.target(context).collectAsState(initial = TranslateLang.FR)

    var book by remember { mutableStateOf<Book?>(null) }
    var chapters by remember { mutableStateOf<List<EpubChapter>>(emptyList()) }
    var chapterIndex by remember { mutableIntStateOf(-1) }
    var blocks by remember { mutableStateOf<List<TextBlock>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showChrome by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var lookupWord by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf(false) }
    var positioned by remember { mutableStateOf(false) }

    // Reading is the one place where coming back to the page beats coming back to the menu.
    SuppressIdleReset()

    LaunchedEffect(bookId) {
        val loaded = dao.getBook(bookId)
        if (loaded == null) {
            error = "Livre introuvable"
            return@LaunchedEffect
        }
        book = loaded
        runCatching { withContext(Dispatchers.IO) { Epub.metadata(bookFile(context, loaded)) } }
            .onSuccess {
                chapters = it.chapters
                chapterIndex = loaded.chapterIndex.coerceIn(0, it.chapters.lastIndex)
            }
            .onFailure { error = userMessage(it, "Livre illisible") }
    }

    LaunchedEffect(chapterIndex, chapters) {
        val loaded = book ?: return@LaunchedEffect
        if (chapterIndex !in chapters.indices) return@LaunchedEffect
        positioned = false
        blocks = null
        val parsed = runCatching {
            withContext(Dispatchers.IO) { Epub.chapter(bookFile(context, loaded), chapters[chapterIndex].href) }
        }.getOrElse {
            error = userMessage(it, "Chapitre illisible")
            emptyList()
        }
        blocks = parsed
        if (!restored) {
            restored = true
            if (loaded.blockIndex in parsed.indices) {
                listState.scrollToItem(loaded.blockIndex, loaded.blockOffset)
            }
        } else {
            listState.scrollToItem(0)
        }
        positioned = true
    }

    // Only once the chapter is actually on screen at the right place, or the first emission would
    // write the previous chapter's scroll position onto the new one.
    LaunchedEffect(bookId, chapterIndex, positioned) {
        if (!positioned || chapterIndex < 0) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                delay(PROGRESS_SAVE_DELAY_MS)
                dao.saveProgress(bookId, chapterIndex, index, offset, System.currentTimeMillis())
            }
    }

    val currentBlocks = blocks

    Box(Modifier.fillMaxSize()) {
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorText(
                    message = error!!,
                    onDismiss = onBack,
                    modifier = Modifier.padding(24.dp)
                )
            }

            currentBlocks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 72.dp)
            ) {
                items(currentBlocks) { block ->
                    BlockText(
                        block = block,
                        fontSize = fontSize,
                        onTap = { showChrome = !showChrome },
                        onLookup = { lookupWord = it }
                    )
                }
                item {
                    ChapterNavigation(
                        chapterIndex = chapterIndex,
                        chapterCount = chapters.size,
                        onGo = { chapterIndex = it }
                    )
                }
            }
        }

        if (showChrome) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                ScreenTopBar(
                    title = chapters.getOrNull(chapterIndex)?.title ?: book?.title.orEmpty(),
                    onBack = onBack,
                    titleStyle = MaterialTheme.typography.titleMedium
                ) {
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { scope.launch { ReaderPrefs.setFontSize(context, fontSize - 1) } },
                        enabled = fontSize > ReaderPrefs.MIN_FONT_SIZE
                    ) {
                        Icon(Icons.Filled.TextDecrease, contentDescription = "Réduire le texte")
                    }
                    IconButton(
                        onClick = { scope.launch { ReaderPrefs.setFontSize(context, fontSize + 1) } },
                        enabled = fontSize < ReaderPrefs.MAX_FONT_SIZE
                    ) {
                        Icon(Icons.Filled.TextIncrease, contentDescription = "Agrandir le texte")
                    }
                    IconButton(onClick = { showToc = true }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Chapitres")
                    }
                }
            }

            if (chapters.isNotEmpty()) {
                Text(
                    text = "${chapterIndex + 1} / ${chapters.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }, sheetState = rememberModalBottomSheetState()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                chapters.forEachIndexed { index, chapter ->
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (index == chapterIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == chapterIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showToc = false
                                chapterIndex = index
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }
    }

    lookupWord?.let { word ->
        WordLookupSheet(word = word, target = lookupTarget, onDismiss = { lookupWord = null })
    }
}

@Composable
private fun ChapterNavigation(chapterIndex: Int, chapterCount: Int, onGo: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MyButton(
            text = "Précédent",
            modifier = Modifier.weight(1f),
            height = 56.dp,
            fontSize = 16.sp,
            enabled = chapterIndex > 0,
            onClick = { onGo(chapterIndex - 1) }
        )
        MyButton(
            text = "Suivant",
            modifier = Modifier.weight(1f),
            height = 56.dp,
            fontSize = 16.sp,
            enabled = chapterIndex < chapterCount - 1,
            onClick = { onGo(chapterIndex + 1) }
        )
    }
}

@Composable
private fun BlockText(
    block: TextBlock,
    fontSize: Int,
    onTap: () -> Unit,
    onLookup: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var layout by remember(block) { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(block) {
        buildAnnotatedString {
            append(block.text)
            block.spans.forEach { span ->
                addStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null
                    ),
                    span.start,
                    span.end
                )
            }
        }
    }

    val isHeading = block.kind == BlockKind.Heading
    Text(
        text = annotated,
        fontSize = (if (isHeading) fontSize + 4 else fontSize).sp,
        lineHeight = (fontSize * 1.6f).sp,
        fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (block.kind == BlockKind.Quote) FontStyle.Italic else FontStyle.Normal,
        textAlign = if (isHeading) TextAlign.Center else TextAlign.Justify,
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (isHeading) 24.dp else 0.dp,
                bottom = if (isHeading) 12.dp else 14.dp,
                start = if (block.kind == BlockKind.Quote) 16.dp else 0.dp
            )
            .pointerInput(block) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { position ->
                        val result = layout ?: return@detectTapGestures
                        val word = wordAt(block.text, result.getOffsetForPosition(position))
                        if (word != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLookup(word)
                        }
                    }
                )
            }
    )
}

private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’' || c == '-'

/** The whole word around [offset], or null when the long press landed between words. */
internal fun wordAt(text: String, offset: Int): String? {
    if (offset !in text.indices || !isWordChar(text[offset])) return null
    var start = offset
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = offset
    while (end < text.length && isWordChar(text[end])) end++
    return text.substring(start, end).trim('\'', '’', '-').takeIf { it.isNotBlank() }
}
