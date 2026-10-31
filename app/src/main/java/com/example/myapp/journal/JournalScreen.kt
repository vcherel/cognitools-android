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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.LightIconButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.journalRepository
import com.example.myapp.news.NewsArticle
import com.example.myapp.newsRepository
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

/** What the note dialog is editing: one item's note, or the note on the whole issue when [itemId] is null. */
private data class NoteTarget(val itemId: String?, val title: String, val text: String)

/**
 * The day's journal as one scroll: the sections in the order the laptop wrote them, each item a card
 * whose full text opens on tap, with its rating, its note and its sources always on it. The arrows in
 * the header walk back through the older issues, which can still be rated; the check marks the one
 * shown as read. Opening the screen fetches nothing, only the refresh button does. It reopens on the
 * issue left last, scrolled and expanded as it was, unless a newer unread issue has arrived since.
 */
@Composable
fun JournalScreen(onBack: () -> Unit, onOpenArticle: (String) -> Unit) {
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
            IconButton(onClick = { date = issues[index + 1].date }, enabled = index in 0 until issues.lastIndex) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Numéro précédent")
            }
            IconButton(onClick = { date = issues[index - 1].date }, enabled = index > 0) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Numéro suivant")
            }
            if (issue != null) {
                val isRead = issue.date in read
                IconButton(onClick = { repo.toggleRead(issue.date) }) {
                    Icon(
                        if (isRead) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = if (isRead) "Marquer comme non lu" else "Marquer comme lu"
                    )
                }
            }
            if (loading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = repo::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Actualiser")
                }
            }
        }

        error?.let {
            ErrorText(message = "Erreur: $it", onDismiss = repo::clearError, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (issue == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Chargement du journal…" else "Aucun journal pour l'instant. Actualise pour le récupérer.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        val position = positions.byDate[issue.date] ?: JournalPosition()
        val listState = remember(issue.date) { LazyListState(position.index, position.offset) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collectLatest { (first, offset) ->
                    delay(SCROLL_SAVE_DELAY_MS)
                    repo.saveScroll(issue.date, first, offset)
                }
        }
        DisposableEffect(listState) {
            onDispose { repo.saveScroll(issue.date, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
            item(key = "head-${issue.date}") {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(
                        runCatching { LocalDate.parse(issue.date).format(DAY_FORMAT) }.getOrDefault(issue.date)
                            .replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                        expanded = item.id in position.expanded,
                        onToggle = { repo.toggleExpanded(issue.date, item.id) },
                        rating = feedback.ratings[item.id] ?: 0,
                        note = feedback.notes[item.id],
                        onRate = { repo.rate(issue.date, item.id, it) },
                        onEditNote = { noteTarget = NoteTarget(item.id, item.title, feedback.notes[item.id].orEmpty()) },
                        onSaveTodo = {
                            scope.launch {
                                val text = if (item.meta.isBlank()) item.title else "${item.title} (${item.meta})"
                                val added = runCatching { addToTodoNote(context, text) }.getOrElse {
                                    AppSnackbar.show("Erreur: ${userMessage(it)}")
                                    return@launch
                                }
                                AppSnackbar.show(if (added) "Ajouté à la Todo list" else "Déjà dans la Todo list")
                            }
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
                        "Idées pour le journal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
                    )
                }
                items(issue.ideas, key = { "${issue.date}-${it.id}" }) { idea ->
                    IdeaCard(idea, feedback.ideas[idea.id]) { repo.answerIdea(issue.date, idea.id, it) }
                }
            }
            item(key = "general-${issue.date}") {
                TextButton(
                    onClick = { noteTarget = NoteTarget(null, "Un mot sur ce numéro", feedback.general) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                ) {
                    Text(if (feedback.general.isBlank()) "Un mot sur ce numéro" else "« ${feedback.general} »")
                }
            }
        }
    }

    noteTarget?.let { target ->
        val date = issue?.date ?: return@let
        NoteDialog(
            target = target,
            onDismiss = { noteTarget = null },
            onSave = { text ->
                if (target.itemId == null) repo.setGeneral(date, text) else repo.setNote(date, target.itemId, text)
                noteTarget = null
            }
        )
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
                    "Essai · ${item.subject}",
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
            if (expanded && item.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(item.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
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
                    "« $note »",
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
                    contentDescription = "Je n'aime pas",
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
                    contentDescription = "Ajouter à la Todo list",
                    onClick = onSaveTodo,
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (item.body.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Réduire" else "Lire la suite",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}

/** An item's picture, cropped to [modifier]'s size, gone altogether when it fails to load. */
@Composable
private fun ItemPicture(url: String, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun IdeaCard(idea: JournalIdea, answer: IdeaAnswer?, onAnswer: (IdeaAnswer) -> Unit) {
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
            }
            Spacer(Modifier.width(4.dp))
            LightIconButton(
                if (answer == IdeaAnswer.ACCEPTED) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = "Accepter",
                onClick = { onAnswer(IdeaAnswer.ACCEPTED) },
                size = 44.dp,
                tint = if (answer == IdeaAnswer.ACCEPTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            LightIconButton(
                if (answer == IdeaAnswer.DECLINED) Icons.Filled.Cancel else Icons.Outlined.Cancel,
                contentDescription = "Refuser",
                onClick = { onAnswer(IdeaAnswer.DECLINED) },
                size = 44.dp,
                tint = if (answer == IdeaAnswer.DECLINED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
            placeholder = { Text("Trop long, déjà vu, plus de détails…") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Annuler") }
            TextButton(onClick = { onSave(text) }) { Text("Enregistrer") }
        }
    }
}
