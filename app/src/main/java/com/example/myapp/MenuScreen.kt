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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
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
    onOpenUndercover: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenRandom: () -> Unit,
    onOpenWikipedia: () -> Unit,
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
                MyButton(text = "Volume", modifier = Modifier.weight(1f), height = buttonHeight, onClick = onOpenVolume)
                MyButton(text = "Divers", modifier = Modifier.weight(1f), height = buttonHeight, onClick = { showOtherTools = true })
            }
            if (showOtherTools) {
                OtherToolsSheet(
                    onDismiss = { showOtherTools = false },
                    onOpenUndercover = onOpenUndercover,
                    onOpenRandom = onOpenRandom,
                    onOpenWikipedia = onOpenWikipedia
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
// the room. A sheet rather than a screen: one tap in, one tap back out.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherToolsSheet(
    onDismiss: () -> Unit,
    onOpenUndercover: () -> Unit,
    onOpenRandom: () -> Unit,
    onOpenWikipedia: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MyButton(text = "Undercover", height = 72.dp, onClick = { onDismiss(); onOpenUndercover() })
            MyButton(text = "Wiki", height = 72.dp, onClick = { onDismiss(); onOpenWikipedia() })
            MyButton(text = "Random", height = 72.dp, onClick = { onDismiss(); onOpenRandom() })
        }
    }
}

// The right half shuffles the favorites straight from the menu, and spins until a new track is
// actually playing, since connecting to the service and resolving the first stream takes a moment.
@Composable
private fun DeezerMenuButton(height: Dp, onOpenDeezer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isShuffleLoading by remember { mutableStateOf(false) }

    SplitMyButton(
        text = "Musique",
        rightIcon = Icons.Default.Shuffle,
        height = height,
        rightLoading = isShuffleLoading,
        onMainClick = onOpenDeezer,
        onRightClick = {
            if (!isShuffleLoading) {
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
