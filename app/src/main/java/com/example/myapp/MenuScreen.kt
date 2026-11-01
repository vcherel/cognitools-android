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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

// One of the small grid tools. [id] is what the usage counters are keyed by and never changes;
// [route] is where tapping it goes, which is the same string for all but two of them.
private class MenuTool(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val route: String = id
)

private val NotesColor = Color(0xFFE3A72F)
private val MusicColor = Color(0xFFE0457B)
private val FlashcardsColor = Color(0xFF3F74D6)
private val GalleryColor = Color(0xFF2FA383)
private val WorkoutColor = Color(0xFF2E9E6A)

// Big tool heights against a grid row's, and the gap that leaves room for the raised edge.
private const val BIG_ROW_WEIGHT = 1.25f
private val RowGap = 16.dp

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
        MenuTool("weather", "Weather", Icons.Default.WbSunny, Color(0xFF3D8FD6)),
        MenuTool("motsFleches", "Crosswords", Icons.Default.GridOn, Color(0xFF6F7A86)),
        MenuTool("translate", "Translate", Icons.Default.Translate, Color(0xFF1F9DB0)),
        MenuTool("reader", "Reader", Icons.AutoMirrored.Filled.MenuBook, Color(0xFFA8703A)),
        MenuTool("volume", "Volume", Icons.AutoMirrored.Filled.VolumeUp, Color(0xFFDB6F33), route = "volumeBooster"),
        MenuTool("undercover", "Undercover", Icons.Default.TheaterComedy, Color(0xFF5F5F8F)),
        MenuTool("wikipedia", "Wiki", Icons.Default.Public, Color(0xFF4F8F4F)),
        MenuTool("random", "Random", Icons.Default.Casino, Color(0xFFC2509A), route = "randomGenerator"),
        MenuTool("files", "Files", Icons.Default.Folder, Color(0xFFC99A1E)),
        MenuTool("news", "News", Icons.Default.Newspaper, Color(0xFF7A63CC)),
        MenuTool("journal", "Journal", Icons.AutoMirrored.Filled.Article, Color(0xFFE58A2B)),
        MenuTool("mail", "Mail", Icons.Default.Mail, Color(0xFF5A6FD6)),
        MenuTool("workout", "Workout", Icons.Default.FitnessCenter, WorkoutColor),
        MenuTool("calendar", "Calendar", Icons.Default.CalendarMonth, Color(0xFFD64B4B))
    )
    // Most used first, alphabetical between tools opened as often (so a fresh install is A to Z).
    val orderedTools = tools.sortedWith(
        compareByDescending<MenuTool> { usage[it.id] ?: 0 }.thenBy { it.label.deaccented() }
    )
    val bigFontSize = 21.sp
    val bigRightWidth = 70.dp
    val bigRowModifier = Modifier.fillMaxWidth()

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
            modifier = bigRowModifier.weight(BIG_ROW_WEIGHT),
            height = Dp.Unspecified,
            rightWidth = bigRightWidth,
            fontSize = bigFontSize,
            chipIcon = Icons.AutoMirrored.Filled.StickyNote2,
            chipColor = NotesColor,
            rightTint = NotesColor,
            onMainClick = onOpenNotes,
            onRightClick = onOpenTodoNote
        )
        DeezerMenuButton(
            modifier = bigRowModifier.weight(BIG_ROW_WEIGHT),
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
        var dueCount by remember { mutableIntStateOf(0) }
        LaunchedEffect(now) {
            context.flashcardRepository.observeDueCount(now).collect { dueCount = it }
        }
        SplitMyButton(
            text = "Flashcards",
            rightIcon = Icons.Default.PlayArrow,
            modifier = bigRowModifier.weight(BIG_ROW_WEIGHT),
            rightText = dueCount.toString(),
            rightEnabled = dueCount > 0,
            height = Dp.Unspecified,
            rightWidth = bigRightWidth,
            fontSize = bigFontSize,
            chipIcon = Icons.Default.Style,
            chipColor = FlashcardsColor,
            rightTint = FlashcardsColor,
            onMainClick = onOpenFlashcards,
            onRightClick = onPlayFlashcards
        )
        val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
        val pinnedRows by remember { pinDao.observePinned() }.collectAsState(initial = emptyList())
        val somethingPinned = pinnedRows.isNotEmpty()
        SplitMyButton(
            text = "Gallery",
            rightIcon = if (somethingPinned) Icons.Default.PushPin else Icons.Default.AccountBalanceWallet,
            modifier = bigRowModifier.weight(BIG_ROW_WEIGHT),
            height = Dp.Unspecified,
            rightWidth = bigRightWidth,
            fontSize = bigFontSize,
            chipIcon = Icons.Default.PhotoLibrary,
            chipColor = GalleryColor,
            rightTint = GalleryColor,
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
                    val open = {
                        scope.launch { usageStore.recordClick(tool.id) }
                        onOpenTool(tool.route)
                    }
                    // Workout's right half logs a session on the spot, without opening the screen.
                    if (tool.id == "workout") {
                        SplitMyButton(
                            text = tool.label,
                            rightIcon = Icons.Default.Add,
                            modifier = Modifier.weight(1f),
                            height = Dp.Unspecified,
                            rightWidth = 40.dp,
                            fontSize = 16.sp,
                            chipIcon = tool.icon,
                            chipColor = tool.color,
                            rightTint = tool.color,
                            onMainClick = { open() },
                            onRightClick = { scope.launch { WorkoutReminder.markDoneWithUndo(context) } }
                        )
                    } else {
                        MyButton(
                            text = tool.label,
                            modifier = Modifier.weight(1f),
                            height = Dp.Unspecified,
                            fontSize = 16.sp,
                            highlighted = tool.id == "journal" && journalUnread,
                            chipIcon = tool.icon,
                            chipColor = tool.color,
                            onClick = { open() }
                        )
                    }
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
        chipIcon = Icons.Default.MusicNote,
        chipColor = MusicColor,
        rightTint = MusicColor,
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
