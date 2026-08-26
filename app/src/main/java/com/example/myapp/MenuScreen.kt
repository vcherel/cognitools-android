package com.example.myapp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.flashcards.isDue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// One of the small grid tools. [id] is what the usage counters are keyed by and never changes;
// [route] is where tapping it goes, which is the same string for all but two of them.
private class MenuTool(val id: String, val label: String, val route: String = id)

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
    val spaceHeight = 20.dp
    val buttonHeight = 84.dp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usageStore = remember(context) { MenuUsageStore(context) }
    val usage by usageStore.counts.collectAsState(initial = emptyMap())

    val tools = listOf(
        MenuTool("weather", "Météo"),
        MenuTool("motsFleches", "Mots fléchés"),
        MenuTool("translate", "Traducteur"),
        MenuTool("reader", "Lecture"),
        MenuTool("volume", "Volume", route = "volumeBooster"),
        MenuTool("undercover", "Undercover"),
        MenuTool("wikipedia", "Wiki"),
        MenuTool("random", "Random", route = "randomGenerator"),
        MenuTool("files", "Fichiers"),
        MenuTool("news", "Actus")
    )
    // Most used first, alphabetical between tools opened as often (so a fresh install is A to Z).
    val orderedTools = tools.sortedWith(
        compareByDescending<MenuTool> { usage[it.id] ?: 0 }.thenBy { it.label.deaccented() }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bienvenue !",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(25.dp))
            Text(
                text = "Choisis une option pour commencer :",
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            SplitMyButton(
                text = "Notes",
                rightIcon = Icons.Default.Checklist,
                height = buttonHeight,
                onMainClick = onOpenNotes,
                onRightClick = onOpenTodoNote
            )
            Spacer(modifier = Modifier.height(spaceHeight))
            DeezerMenuButton(height = buttonHeight, onOpenDeezer = onOpenDeezer)
            Spacer(modifier = Modifier.height(spaceHeight))
            // The right half shows how many cards are due right now instead of a play icon.
            val allCards by context.flashcardRepository.observeAllElements().collectAsState(initial = emptyList())
            // Cards come due while the menu sits open (the app returns to it when idle), so the
            // count is recomputed every minute rather than only when the list itself changes.
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(60_000)
                    now = System.currentTimeMillis()
                }
            }
            val dueCount = allCards.count { isDue(it, now) }
            SplitMyButton(
                text = "Flashcards",
                rightIcon = Icons.Default.PlayArrow,
                rightText = dueCount.toString(),
                rightEnabled = dueCount > 0,
                height = buttonHeight,
                onMainClick = onOpenFlashcards,
                onRightClick = onPlayFlashcards
            )
            Spacer(modifier = Modifier.height(spaceHeight))
            val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
            val pinnedRows by pinDao.observePinned().collectAsState(initial = emptyList())
            val somethingPinned = pinnedRows.isNotEmpty()
            SplitMyButton(
                text = "Galerie",
                rightIcon = if (somethingPinned) Icons.Default.PushPin else Icons.Default.AccountBalanceWallet,
                height = buttonHeight,
                onMainClick = onOpenGallery,
                onRightClick = if (somethingPinned) onOpenPinnedPictures else onOpenWallet
            )
            Spacer(modifier = Modifier.height(spaceHeight))
            orderedTools.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { tool ->
                        MyButton(
                            text = tool.label,
                            modifier = Modifier.weight(1f),
                            height = buttonHeight,
                            fontSize = 20.sp,
                            onClick = {
                                scope.launch { usageStore.recordClick(tool.id) }
                                onOpenTool(tool.route)
                            }
                        )
                    }
                    repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
                Spacer(modifier = Modifier.height(spaceHeight))
            }
        }

        IconButton(
            onClick = onToggleDarkMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = 8.dp)
        ) {
            Icon(
                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = if (isDarkMode) "Mode clair" else "Mode sombre"
            )
        }
    }
}

// The right half reflects what is playing, music or podcast: pause while it plays, play to resume a
// loaded track, and shuffle the favorites when nothing is loaded. The shuffle spins until a new track
// is actually playing, since connecting to the service and resolving the first stream takes a moment.
@Composable
private fun DeezerMenuButton(height: Dp, onOpenDeezer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isShuffleLoading by remember { mutableStateOf(false) }
    val musicState by context.deezerRepository.playerState.collectAsState()
    val podcastState by context.podcastRepository.playerState.collectAsState()

    // The two stacks are mutually exclusive, so at most one is really loaded; whichever is playing
    // still wins, in case the other one left a stale item behind.
    val onPodcast = podcastState.isPlaying || (podcastState.hasItem && !musicState.isPlaying)
    val loaded = if (onPodcast) podcastState.hasItem else musicState.hasItem
    val isPlaying = if (onPodcast) podcastState.isPlaying else musicState.isPlaying

    SplitMyButton(
        text = "Musique",
        rightIcon = when {
            !loaded -> Icons.Default.Shuffle
            isPlaying -> Icons.Default.Pause
            else -> Icons.Default.PlayArrow
        },
        height = height,
        rightLoading = isShuffleLoading,
        onMainClick = onOpenDeezer,
        onRightClick = {
            if (loaded) {
                if (onPodcast) context.podcastRepository.togglePlay() else context.deezerRepository.togglePlay()
            } else if (!isShuffleLoading) {
                val repo = context.deezerRepository
                val previousSngId = repo.playerState.value.sngId
                isShuffleLoading = true
                scope.launch {
                    runCatching {
                        repo.shuffleFavorites()
                        withTimeoutOrNull(15_000) {
                            repo.playerState.first { it.isPlaying && it.sngId != previousSngId }
                        }
                    }.onFailure {
                        Toast.makeText(context, "Erreur: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                    isShuffleLoading = false
                }
            }
        }
    )
}
