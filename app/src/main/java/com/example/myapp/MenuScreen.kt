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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onOpenUndercover: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenRandom: () -> Unit,
    onOpenWikipedia: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenWallet: () -> Unit
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
                MyButton(text = "Undercover", modifier = Modifier.weight(1f), height = buttonHeight, onClick = onOpenUndercover)
            }
            Spacer(modifier = Modifier.height(spaceHeight))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(text = "Volume", modifier = Modifier.weight(1f), height = buttonHeight, onClick = onOpenVolume)
                MyButton(text = "Random", modifier = Modifier.weight(1f), height = buttonHeight, onClick = onOpenRandom)
                MyButton(text = "Wiki", modifier = Modifier.weight(1f), height = buttonHeight, onClick = onOpenWikipedia)
            }
            Spacer(modifier = Modifier.height(spaceHeight))
            SplitMyButton(
                text = "Galerie",
                rightIcon = Icons.Default.AccountBalanceWallet,
                height = buttonHeight,
                onMainClick = onOpenGallery,
                onRightClick = onOpenWallet
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

// The right half shuffles the favorites straight from the menu, and spins until a new track is
// actually playing, since connecting to the service and resolving the first stream takes a moment.
@Composable
private fun DeezerMenuButton(height: Dp, onOpenDeezer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isShuffleLoading by remember { mutableStateOf(false) }

    SplitMyButton(
        text = "Deezer",
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
