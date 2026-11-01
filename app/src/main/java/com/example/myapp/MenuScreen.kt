package com.example.myapp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.workout.WorkoutReminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// One of the small grid tools. [id] is its nav route and what the usage counters are keyed by.
private class MenuTool(val id: String, val label: String, val accent: ToolAccent)

// Big tool heights against a grid row's, and the gap that leaves room for the raised edge.
private const val BIG_ROW_WEIGHT = 1.25f
private val RowGap = 16.dp

// What the Gallery tile showed last, so coming back to the menu does not flash the wallet icon
// before the pinned query answers.
private var lastSomethingPinned = false

@Composable
fun MenuScreen(
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenTodoNote: () -> Unit,
    onOpenDeezer: () -> Unit,
    onOpenFlashcards: () -> Unit,
    onPlayFlashcards: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenPinnedPictures: () -> Unit,
    onOpenTool: (route: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usageStore = remember(context) { MenuUsageStore(context) }
    val usage by usageStore.counts.collectAsState(initial = emptyMap())

    val tools = listOf(
        MenuTool("weather", "Weather", ToolAccents.Weather),
        MenuTool("motsFleches", "Crosswords", ToolAccents.Crosswords),
        MenuTool("translate", "Translate", ToolAccents.Translate),
        MenuTool("reader", "Reader", ToolAccents.Reader),
        MenuTool("volume", "Volume", ToolAccents.Volume),
        MenuTool("undercover", "Undercover", ToolAccents.Undercover),
        MenuTool("wikipedia", "Wiki", ToolAccents.Wiki),
        MenuTool("random", "Random", ToolAccents.Random),
        MenuTool("files", "Files", ToolAccents.Files),
        MenuTool("journal", "Journal", ToolAccents.Journal),
        MenuTool("mail", "Mail", ToolAccents.Mail),
        MenuTool("workout", "Workout", ToolAccents.Workout),
        MenuTool("calendar", "Calendar", ToolAccents.Calendar)
    )
    // Most used first, alphabetical between tools opened as often (so a fresh install is A to Z).
    val orderedTools = tools.sortedWith(
        compareByDescending<MenuTool> { usage[it.id] ?: 0 }.thenBy { it.label.deaccented() }
    )
    val bigFontSize = 21.sp
    val bigRightWidth = 70.dp

    // No scroll: the rows share the screen height, the big tools a little taller than the grid's.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(RowGap)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CogniTools",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            BackupMenuButton()
            IconButton(onClick = onToggleDarkMode) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isDarkMode) "Light mode" else "Dark mode"
                )
            }
        }
        SplitMyButton(
            text = "Notes",
            rightIcon = Icons.Default.Checklist,
            modifier = Modifier.weight(BIG_ROW_WEIGHT),
            height = Dp.Unspecified,
            rightWidth = bigRightWidth,
            fontSize = bigFontSize,
            chipIcon = ToolAccents.Notes.icon,
            chipColor = ToolAccents.Notes.color,
            rightTint = ToolAccents.Notes.color,
            onMainClick = onOpenNotes,
            onRightClick = onOpenTodoNote
        )
        DeezerMenuButton(
            modifier = Modifier.weight(BIG_ROW_WEIGHT),
            rightWidth = bigRightWidth,
            fontSize = bigFontSize,
            onOpenDeezer = onOpenDeezer
        )
        // The right half shows how many cards are due right now instead of a play icon.
        // Cards come due while the menu sits open (the app returns to it when idle), so the
        // count is requeried every minute rather than only when the cards themselves change.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                now = System.currentTimeMillis()
            }
        }
        var dueCount by remember { mutableIntStateOf(context.flashcardRepository.lastDueCount ?: 0) }
        LaunchedEffect(now) {
            context.flashcardRepository.observeDueCount(now).collect { dueCount = it }
        }
        SplitMyButton(
            text = "Flashcards",
            modifier = Modifier.weight(BIG_ROW_WEIGHT),
            rightText = dueCount.toString(),
            rightEnabled = dueCount > 0,
            height = Dp.Unspecified,
            rightWidth = bigRightWidth,
            fontSize = bigFontSize,
            chipIcon = ToolAccents.Flashcards.icon,
            chipColor = ToolAccents.Flashcards.color,
            rightTint = ToolAccents.Flashcards.color,
            onMainClick = onOpenFlashcards,
            onRightClick = onPlayFlashcards
        )
        val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
        val pinnedRows by remember { pinDao.observePinned() }.collectAsState(initial = null)
        val somethingPinned = pinnedRows?.isNotEmpty() ?: lastSomethingPinned
        lastSomethingPinned = somethingPinned
        SplitMyButton(
            text = "Gallery",
            rightIcon = if (somethingPinned) Icons.Default.PushPin else Icons.Default.AccountBalanceWallet,
            modifier = Modifier.weight(BIG_ROW_WEIGHT),
            height = Dp.Unspecified,
            rightWidth = bigRightWidth,
            fontSize = bigFontSize,
            chipIcon = ToolAccents.Gallery.icon,
            chipColor = ToolAccents.Gallery.color,
            rightTint = ToolAccents.Gallery.color,
            onMainClick = onOpenGallery,
            onRightClick = if (somethingPinned) onOpenPinnedPictures else onOpenWallet
        )
        val journalUnread by context.journalRepository.unread.collectAsState()
        orderedTools.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { tool ->
                    MyButton(
                        text = tool.label,
                        modifier = Modifier.weight(1f),
                        height = Dp.Unspecified,
                        fontSize = 16.sp,
                        highlighted = tool.id == "journal" && journalUnread,
                        chipIcon = tool.accent.icon,
                        chipColor = tool.accent.color,
                        // A long press on Workout logs a session on the spot, without opening the screen.
                        onLongClick = if (tool.id == "workout") {
                            { scope.launch { WorkoutReminder.markDoneWithUndo(context) } }
                        } else null,
                        onClick = {
                            scope.launch { usageStore.recordClick(tool.id) }
                            onOpenTool(tool.id)
                        }
                    )
                }
                repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// The right half reflects what is playing, music or podcast: pause while it plays, play to resume a
// loaded track, and shuffle the favorites when nothing is loaded. The shuffle spins until a new track
// is actually playing, since connecting to the service and resolving the first stream takes a moment.
@Composable
private fun DeezerMenuButton(
    modifier: Modifier,
    rightWidth: Dp,
    fontSize: TextUnit,
    onOpenDeezer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isShuffleLoading by remember { mutableStateOf(false) }
    val musicState by context.deezerRepository.player.playerState.collectAsState()
    val podcastState by context.podcastRepository.playerState.collectAsState()

    // The two stacks are mutually exclusive, so at most one is really loaded; whichever is playing
    // still wins, in case the other one left a stale item behind.
    val onPodcast = podcastState.isPlaying || (podcastState.hasItem && !musicState.isPlaying)
    val loaded = if (onPodcast) podcastState.hasItem else musicState.hasItem
    val isPlaying = if (onPodcast) podcastState.isPlaying else musicState.isPlaying

    LaunchedEffect(loaded) {
        if (!loaded) context.deezerRepository.player.warmUp()
    }

    SplitMyButton(
        text = "Music",
        rightIcon = when {
            !loaded -> Icons.Default.Shuffle
            isPlaying -> Icons.Default.Pause
            else -> Icons.Default.PlayArrow
        },
        modifier = modifier,
        height = Dp.Unspecified,
        rightWidth = rightWidth,
        fontSize = fontSize,
        chipIcon = ToolAccents.Music.icon,
        chipColor = ToolAccents.Music.color,
        rightTint = ToolAccents.Music.color,
        rightLoading = isShuffleLoading,
        onMainClick = onOpenDeezer,
        onRightClick = {
            if (loaded) {
                if (onPodcast) context.podcastRepository.togglePlay() else context.deezerRepository.player.togglePlay()
            } else if (!isShuffleLoading) {
                val repo = context.deezerRepository
                val previousSngId = repo.player.playerState.value.sngId
                isShuffleLoading = true
                scope.launch {
                    runCatching {
                        repo.player.shuffleFavoritesDetached().await()
                        withTimeoutOrNull(15_000) {
                            repo.player.playerState.first { it.isPlaying && it.sngId != previousSngId }
                        }
                    }.onFailure {
                        Toast.makeText(context, userMessage(it, "Error"), Toast.LENGTH_SHORT).show()
                    }
                    isShuffleLoading = false
                }
            }
        }
    )
}
