package com.example.myapp.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.LightIconButton
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.journalRepository
import com.example.myapp.notes.formatInline
import com.example.myapp.news.NewsArticle
import com.example.myapp.newsRepository
import com.example.myapp.notes.WEEKEND_SECTION
import com.example.myapp.notes.addToTodoNote
import com.example.myapp.userMessage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private const val SCROLL_SAVE_DELAY_MS = 500L

/**
 * What the note dialog is editing: one item's note, an idea's comment when [idea] is set, or the note
 * on the whole issue when [id] is null.
 */
private data class NoteTarget(val id: String?, val title: String, val text: String, val idea: Boolean = false)

/** The item the Todo list dialog is filing, and the line it writes. */
private data class TodoTarget(val itemId: String, val text: String)

/**
 * The day's journal as one scroll: the sections in the order the laptop wrote them, each item a card
 * whose full text opens on tap, with its rating, its note and its sources always on it. The arrows
 * around the date walk back through the older issues, which can still be rated; the button at the
 * end marks the one shown as read. Opening the screen fetches nothing, only the refresh button does. It reopens on the
 * issue left last, scrolled as it was with every card collapsed, unless a newer unread issue has arrived since.
 */
@Composable
fun JournalScreen(onBack: () -> Unit, onOpenArticle: (String) -> Unit, onOpenNews: () -> Unit) {
    val context = LocalContext.current
    val repo = context.journalRepository
    val issues by repo.issues.collectAsState()
    val allFeedback by repo.feedback.collectAsState()
    val loading by repo.loading.collectAsState()
    val error by repo.error.collectAsState()
    val read by repo.read.collectAsState()
    val positions by repo.positions.collectAsState()

    var date by rememberSaveable { mutableStateOf<String?>(null) }
    var noteTarget by remember { mutableStateOf<NoteTarget?>(null) }
    var todoTarget by remember { mutableStateOf<TodoTarget?>(null) }
    val scope = rememberCoroutineScope()
    val shownDate = date?.takeIf { d -> issues.any { it.date == d } } ?: repo.startDate()
    val index = issues.indexOfFirst { it.date == shownDate }
    val issue = issues.getOrNull(index)
    val feedback = issue?.let { allFeedback[it.date] } ?: JournalFeedback()

    LaunchedEffect(shownDate) {
        if (shownDate == null) return@LaunchedEffect
        date = shownDate
        repo.setShown(shownDate)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Journal",
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleLarge,
            titleWeight = true
        ) {
            IconButton(onClick = onOpenNews) {
                Icon(Icons.Filled.Newspaper, contentDescription = "News")
            }
            if (loading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = repo::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        error?.let {
            ErrorText(message = "Error: $it", onDismiss = repo::clearError, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (issue == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Loading the journal…" else "No journal yet. Refresh to fetch it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        val position = positions.byDate[issue.date] ?: JournalPosition()
        val listState = remember(issue.date) { LazyListState(position.index, position.offset) }
        var expanded by remember(issue.date) { mutableStateOf(emptySet<String>()) }
        // Cards reopen collapsed, so a row left halfway through an open card is saved at its top.
        fun saveScroll() {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val open = first != null && expanded.any { first.key == "${issue.date}-$it" }
            repo.saveScroll(issue.date, listState.firstVisibleItemIndex, if (open) 0 else listState.firstVisibleItemScrollOffset)
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collectLatest {
                    delay(SCROLL_SAVE_DELAY_MS)
                    saveScroll()
                }
        }
        DisposableEffect(listState) {
            onDispose { saveScroll() }
        }
        // Collapsing a card read past its top brings that top back first, or the rows below jump up past the finger.
        fun toggle(itemId: String) {
            if (itemId !in expanded) {
                expanded = expanded + itemId
                return
            }
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "${issue.date}-$itemId" }
            if (info == null || info.offset >= 0) {
                expanded = expanded - itemId
                return
            }
            scope.launch {
                listState.scrollToItem(info.index)
                expanded = expanded - itemId
            }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
            item(key = "head-${issue.date}") {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { date = issues[index + 1].date }, enabled = index < issues.lastIndex) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous issue")
                        }
                        Text(
                            runCatching { LocalDate.parse(issue.date).format(DAY_FORMAT) }.getOrDefault(issue.date)
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { date = issues[index - 1].date }, enabled = index > 0) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Next issue")
                        }
                    }
                    if (issue.headline.isNotBlank()) {
                        Text(issue.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            issue.sections.forEach { section ->
                item(key = "section-${issue.date}-${section.id}") { SectionHeader(section) }
                items(section.items, key = { "${issue.date}-${it.id}" }) { item ->
                    JournalItemCard(
                        item = item,
                        explore = section.kind == SectionKind.EXPLORE,
                        expanded = item.id in expanded,
                        onToggle = { toggle(item.id) },
                        rating = feedback.ratings[item.id] ?: 0,
                        note = feedback.notes[item.id],
                        onRate = { repo.rate(issue.date, item.id, it) },
                        onEditNote = { noteTarget = NoteTarget(item.id, item.title, feedback.notes[item.id].orEmpty()) },
                        onSaveTodo = {
                            val fallback = if (item.meta.isBlank()) item.title else "${item.title} (${item.meta})"
                            todoTarget = TodoTarget(item.id, item.todoLine.ifBlank { fallback })
                        },
                        onOpenSource = { source ->
                            repo.markOpened(issue.date, item.id)
                            context.newsRepository.registerOutside(
                                NewsArticle(source.url, item.title, item.summary, null, source.name, "journal", 0L)
                            )
                            onOpenArticle(source.url)
                        }
                    )
                }
            }
            if (issue.ideas.isNotEmpty()) {
                item(key = "ideas-${issue.date}") {
                    Text(
                        "Ideas for the journal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
                    )
                }
                items(issue.ideas, key = { "${issue.date}-${it.id}" }) { idea ->
                    IdeaCard(
                        idea = idea,
                        answer = feedback.ideas[idea.id],
                        note = feedback.ideaNotes[idea.id],
                        onAnswer = { repo.answerIdea(issue.date, idea.id, it) },
                        onEditNote = { noteTarget = NoteTarget(idea.id, idea.text, feedback.ideaNotes[idea.id].orEmpty(), idea = true) }
                    )
                }
            }
            item(key = "general-${issue.date}") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp)
                ) {
                    Row(
                        Modifier
                            .clickable { noteTarget = NoteTarget(null, "A word on this issue", feedback.general) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (feedback.general.isBlank()) Icons.Outlined.AddComment else Icons.AutoMirrored.Filled.Comment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        if (feedback.general.isBlank()) {
                            Text(
                                "A word on this issue",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text("“${feedback.general}”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                        }
                    }
                }
            }
            item(key = "read-${issue.date}") {
                val isRead = issue.date in read
                MyButton(
                    text = if (isRead) "Read" else "Mark as read",
                    fontSize = 18.sp,
                    height = 56.dp,
                    secondary = isRead,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    onClick = { repo.toggleRead(issue.date) }
                )
            }
        }
    }

    noteTarget?.let { target ->
        val date = issue?.date ?: return@let
        NoteDialog(
            target = target,
            onDismiss = { noteTarget = null },
            onSave = { text ->
                when {
                    target.id == null -> repo.setGeneral(date, text)
                    target.idea -> repo.setIdeaNote(date, target.id, text)
                    else -> repo.setNote(date, target.id, text)
                }
                noteTarget = null
            }
        )
    }

    todoTarget?.let { target ->
        val date = issue?.date ?: return@let
        TodoTargetDialog(
            text = target.text,
            onDismiss = { todoTarget = null },
            onPick = { section ->
                todoTarget = null
                scope.launch {
                    val added = runCatching { addToTodoNote(context, target.text, section) }.getOrElse {
                        AppSnackbar.show("Error: ${userMessage(it)}")
                        return@launch
                    }
                    repo.markTodo(date, target.itemId)
                    val where = section ?: "Todo list"
                    AppSnackbar.show(if (added) "Added to $where" else "Already in the Todo list")
                }
            }
        )
    }
}

// Where a journal item goes in the Todo list: the active block, or the Week-end section.
@Composable
private fun TodoTargetDialog(text: String, onDismiss: () -> Unit, onPick: (section: String?) -> Unit) {
    AppDialog(onDismiss = onDismiss) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        listOf(null to "Todo list", WEEKEND_SECTION to WEEKEND_SECTION).forEach { (section, label) ->
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(section) }
                    .padding(vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(section: JournalSection) {
    Text(
        section.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (section.kind == SectionKind.FIXED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun JournalItemCard(
    item: JournalItem,
    explore: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    rating: Int,
    note: String?,
    onRate: (Int) -> Unit,
    onEditNote: () -> Unit,
    onSaveTodo: () -> Unit,
    onOpenSource: (JournalSource) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.clickable(onClick = onToggle).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            if (explore && item.subject.isNotBlank()) {
                Text(
                    "Experiment · ${item.subject}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (item.meta.isNotBlank()) {
                        Text(item.meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!expanded && item.image.isNotBlank()) {
                    ItemPicture(item.image, Modifier.padding(start = 8.dp, end = 8.dp).size(64.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            if (expanded && item.image.isNotBlank()) {
                ItemPicture(item.image, Modifier.padding(top = 8.dp, end = 8.dp).fillMaxWidth().heightIn(max = 240.dp))
            }
            if (expanded && item.diagram.isNotBlank()) {
                ItemPicture(item.diagram, Modifier.padding(top = 8.dp, end = 8.dp).fillMaxWidth(), ContentScale.FillWidth)
            }
            if (expanded && item.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(remember(item.body) { item.body.formatInline() }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.sources.forEach { source ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onOpenSource(source) }
                    ) {
                        Text(
                            source.name.ifBlank { "Source" },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "“$note”",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LightIconButton(
                    if (rating == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "J'aime",
                    onClick = { onRate(1) },
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = if (rating == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                LightIconButton(
                    if (rating == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = "Dislike",
                    onClick = { onRate(-1) },
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = if (rating == -1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                LightIconButton(
                    if (note.isNullOrBlank()) Icons.Outlined.AddComment else Icons.AutoMirrored.Filled.Comment,
                    contentDescription = "Note",
                    onClick = onEditNote,
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LightIconButton(
                    Icons.Outlined.AddTask,
                    contentDescription = "Add to the Todo list",
                    onClick = onSaveTodo,
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (item.body.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Show less" else "Read more",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}

/** An item's picture, cropped to [modifier]'s size unless [scale] says otherwise, gone altogether when it fails to load. */
@Composable
private fun ItemPicture(url: String, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = scale,
        onError = { failed = true },
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun IdeaCard(
    idea: JournalIdea,
    answer: IdeaAnswer?,
    note: String?,
    onAnswer: (IdeaAnswer) -> Unit,
    onEditNote: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(idea.text, style = MaterialTheme.typography.bodyLarge)
                if (idea.reason.isNotBlank()) {
                    Text(idea.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!note.isNullOrBlank()) {
                    Text(
                        "“$note”",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            LightIconButton(
                if (answer == IdeaAnswer.ACCEPTED) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = "Accept",
                onClick = { onAnswer(IdeaAnswer.ACCEPTED) },
                size = 44.dp,
                tint = if (answer == IdeaAnswer.ACCEPTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            LightIconButton(
                if (answer == IdeaAnswer.DECLINED) Icons.Filled.Cancel else Icons.Outlined.Cancel,
                contentDescription = "Decline",
                onClick = { onAnswer(IdeaAnswer.DECLINED) },
                size = 44.dp,
                tint = if (answer == IdeaAnswer.DECLINED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            LightIconButton(
                if (note.isNullOrBlank()) Icons.Outlined.AddComment else Icons.AutoMirrored.Filled.Comment,
                contentDescription = "Comment",
                onClick = onEditNote,
                size = 44.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoteDialog(target: NoteTarget, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(target.text) }
    AppDialog(onDismiss = onDismiss) {
        Text(target.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Too long, seen it, more detail…") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        }
    }
}
