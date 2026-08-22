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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun MenuScreen(
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenTodoNote: () -> Unit,
    onOpenDeezer: () -> Unit,
    onOpenFlashcards: () -> Unit,
    onPlayFlashcards: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenMotsFleches: () -> Unit,
    onOpenTranslate: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenUndercover: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenRandom: () -> Unit,
    onOpenWikipedia: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenPinnedPictures: () -> Unit
) {
    val spaceHeight = 20.dp
    val buttonHeight = 84.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
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
            SplitMyButton(
                text = "Flashcards",
                rightIcon = Icons.Default.PlayArrow,
                height = buttonHeight,
                onMainClick = onOpenFlashcards,
                onRightClick = onPlayFlashcards
            )
            Spacer(modifier = Modifier.height(spaceHeight))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(text = "Météo", modifier = Modifier.weight(1f), height = buttonHeight, onClick = onOpenWeather)
                MyButton(text = "Mots fléchés", modifier = Modifier.weight(1f), height = buttonHeight, fontSize = 20.sp, onClick = onOpenMotsFleches)
            }
            Spacer(modifier = Modifier.height(spaceHeight))
            var showOtherTools by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(text = "Traducteur", modifier = Modifier.weight(1f), height = buttonHeight, fontSize = 20.sp, onClick = onOpenTranslate)
                MyButton(text = "Divers", modifier = Modifier.weight(1f), height = buttonHeight, onClick = { showOtherTools = true })
            }
            if (showOtherTools) {
                OtherToolsSheet(
                    onDismiss = { showOtherTools = false },
                    onOpenReader = onOpenReader,
                    onOpenVolume = onOpenVolume,
                    onOpenUndercover = onOpenUndercover,
                    onOpenRandom = onOpenRandom,
                    onOpenWikipedia = onOpenWikipedia,
                    onOpenFiles = onOpenFiles
                )
            }
            Spacer(modifier = Modifier.height(spaceHeight))
            val context = LocalContext.current
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

// The tools that get opened once in a while live behind one button, so the ones used daily keep
// the room. A sheet rather than a screen: one tap in, one tap back out. Always fully expanded, or
// a list this long opens at half height and the last tools sit below the fold.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherToolsSheet(
    onDismiss: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenUndercover: () -> Unit,
    onOpenRandom: () -> Unit,
    onOpenWikipedia: () -> Unit,
    onOpenFiles: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MyButton(text = "Lecture", height = 72.dp, onClick = { onDismiss(); onOpenReader() })
            MyButton(text = "Volume", height = 72.dp, onClick = { onDismiss(); onOpenVolume() })
            MyButton(text = "Undercover", height = 72.dp, onClick = { onDismiss(); onOpenUndercover() })
            MyButton(text = "Wiki", height = 72.dp, onClick = { onDismiss(); onOpenWikipedia() })
            MyButton(text = "Random", height = 72.dp, onClick = { onDismiss(); onOpenRandom() })
            MyButton(text = "Fichiers", height = 72.dp, onClick = { onDismiss(); onOpenFiles() })
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
