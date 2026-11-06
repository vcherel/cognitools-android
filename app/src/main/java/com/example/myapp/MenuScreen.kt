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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// One menu tool. [id] is what the usage counters are keyed by, and a small tool's nav route.
private class MenuTool(val id: String, val label: String, val accent: ToolAccent)

// Each takes a full row with its quick action beside it.
private val BigTools = listOf(
    MenuTool("notes", "Notes", ToolAccents.Notes),
    MenuTool("music", "Music", ToolAccents.Music),
    MenuTool("flashcards", "Flashcards", ToolAccents.Flashcards),
    MenuTool("gallery", "Gallery", ToolAccents.Gallery)
)

private val SmallTools = listOf(
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
    MenuTool("calendar", "Calendar", ToolAccents.Calendar),
    MenuTool("recipes", "Recipes", ToolAccents.Recipes)
)

private val MenuTool.big get() = this in BigTools

// Splits the ranked tools into rows: a big tool alone, small ones in pairs. A small tool left alone
// before a big one takes the next small tool up with it, so no grid row is half empty.
private fun menuRows(ranked: List<MenuTool>): List<List<MenuTool>> {
    val pending = ranked.toMutableList()
    val rows = mutableListOf<List<MenuTool>>()
    while (pending.isNotEmpty()) {
        val first = pending.removeAt(0)
        val partner = if (first.big) -1 else pending.indexOfFirst { !it.big }
        rows += if (partner >= 0) listOf(first, pending.removeAt(partner)) else listOf(first)
    }
    return rows
}

// Big tool heights against a grid row's, and the gap that leaves room for the raised edge.
private const val BIG_ROW_WEIGHT = 1.25f
private val RowGap = 16.dp
private val BigFontSize = 21.sp
private val BigRightWidth = 70.dp

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
    var showClicks by remember { mutableStateOf(false) }
    fun badge(id: String) = if (showClicks) usage[id] else null

    // Big and small tools ranked together, most used first, A to Z between equal counts.
    val rows = menuRows(
        (BigTools + SmallTools).sortedWith(
            compareByDescending<MenuTool> { usage[it.id] ?: 0 }.thenBy { it.label.deaccented() }
        )
    )

    fun open(id: String, action: () -> Unit) {
        scope.launch { usageStore.recordClick(id) }
        action()
    }

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
    val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
    val pinnedRows by remember { pinDao.observePinned() }.collectAsState(initial = null)
    val somethingPinned = pinnedRows?.isNotEmpty() ?: lastSomethingPinned
    lastSomethingPinned = somethingPinned
    val journalUnread by context.journalRepository.unread.collectAsState()

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
            IconButton(onClick = { showClicks = !showClicks }) {
                Icon(
                    imageVector = if (showClicks) Icons.Default.VisibilityOff else Icons.Default.BarChart,
                    contentDescription = if (showClicks) "Hide clicks" else "Show clicks"
                )
            }
            BackupMenuButton()
            IconButton(onClick = onToggleDarkMode) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isDarkMode) "Light mode" else "Dark mode"
                )
            }
        }
        rows.forEach { row ->
            val lead = row.first()
            val id = lead.id
            key(id) {
                if (!lead.big) Row(
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
                            badge = badge(tool.id),
                            onClick = { open(tool.id) { onOpenTool(tool.id) } }
                        )
                    }
                    repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                } else when (id) {
                    "notes" -> SplitMyButton(
                        text = lead.label,
                        rightIcon = Icons.Default.Checklist,
                        modifier = Modifier.weight(BIG_ROW_WEIGHT),
                        height = Dp.Unspecified,
                        rightWidth = BigRightWidth,
                        fontSize = BigFontSize,
                        chipIcon = lead.accent.icon,
                        chipColor = lead.accent.color,
                        rightTint = lead.accent.color,
                        badge = badge(id),
                        onMainClick = { open(id, onOpenNotes) },
                        onRightClick = { open(id, onOpenTodoNote) }
                    )
                    "music" -> DeezerMenuButton(
                        tool = lead,
                        modifier = Modifier.weight(BIG_ROW_WEIGHT),
                        badge = badge(id),
                        onRightTap = { scope.launch { usageStore.recordClick(id) } },
                        onOpenDeezer = { open(id, onOpenDeezer) }
                    )
                    "flashcards" -> SplitMyButton(
                        text = lead.label,
                        modifier = Modifier.weight(BIG_ROW_WEIGHT),
                        rightText = dueCount.toString(),
                        rightEnabled = dueCount > 0,
                        height = Dp.Unspecified,
                        rightWidth = BigRightWidth,
                        fontSize = BigFontSize,
                        chipIcon = lead.accent.icon,
                        chipColor = lead.accent.color,
                        rightTint = lead.accent.color,
                        badge = badge(id),
                        onMainClick = { open(id, onOpenFlashcards) },
                        onRightClick = { open(id, onPlayFlashcards) }
                    )
                    "gallery" -> SplitMyButton(
                        text = lead.label,
                        rightIcon = if (somethingPinned) Icons.Default.PushPin else Icons.Default.AccountBalanceWallet,
                        modifier = Modifier.weight(BIG_ROW_WEIGHT),
                        height = Dp.Unspecified,
                        rightWidth = BigRightWidth,
                        fontSize = BigFontSize,
                        chipIcon = lead.accent.icon,
                        chipColor = lead.accent.color,
                        rightTint = lead.accent.color,
                        badge = badge(id),
                        onMainClick = { open(id, onOpenGallery) },
                        onRightClick = { open(id, if (somethingPinned) onOpenPinnedPictures else onOpenWallet) }
                    )
                }
            }
        }
    }
}

// The right half reflects what is playing, music or podcast: pause while it plays, play to resume a
// loaded track, and shuffle the favorites when nothing is loaded. The shuffle spins until a new track
// is actually playing, since connecting to the service and resolving the first stream takes a moment.
@Composable
private fun DeezerMenuButton(
    tool: MenuTool,
    modifier: Modifier,
    badge: Int?,
    onRightTap: () -> Unit,
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
        text = tool.label,
        rightIcon = when {
            !loaded -> Icons.Default.Shuffle
            isPlaying -> Icons.Default.Pause
            else -> Icons.Default.PlayArrow
        },
        modifier = modifier,
        height = Dp.Unspecified,
        rightWidth = BigRightWidth,
        fontSize = BigFontSize,
        chipIcon = tool.accent.icon,
        chipColor = tool.accent.color,
        rightTint = tool.accent.color,
        rightLoading = isShuffleLoading,
        badge = badge,
        onMainClick = onOpenDeezer,
        onRightClick = {
            onRightTap()
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
