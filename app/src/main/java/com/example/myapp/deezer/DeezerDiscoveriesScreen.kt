package com.example.myapp.deezer

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.ErrorText
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch

/**
 * The day's twenty proposals. Each row can be liked (which sends it to Favoris) or thrown away, both
 * of which remove it from the list; the two header buttons do the same for the whole batch at once.
 * The refresh button in the top bar rolls a new set of discoveries as often as wanted, keeping the new
 * releases in place. When nothing is left the list stays empty until tomorrow's batch.
 */
@Composable
fun DeezerDiscoveriesScreen(repo: DeezerRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val discoveries = repo.discoveries
    val state by discoveries.state.collectAsState()
    val playerState by repo.playerState.collectAsState()
    val tracks = state.tracks

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Découvertes du jour", onBack = onBack) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { discoveries.regenerate() }, enabled = !state.generating) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Renouveler les découvertes",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        state.error?.let {
            ErrorText(
                message = "Erreur: $it",
                onDismiss = { discoveries.clearError() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        when {
            tracks.isEmpty() && !state.generating -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Tout est traité. La prochaine sélection arrive demain, ou tout de suite avec le bouton renouveler.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                // Building a batch never blocks the screen: the list stays usable and the progress
                // is just this discreet line at the top.
                if (state.generating) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(
                                if (tracks.isEmpty()) "Recherche de nouveautés… ${state.progress} %"
                                else "Renouvellement… ${state.progress} %",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp)
                            )
                        }
                    }
                }
                if (tracks.isNotEmpty()) item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MyButton(
                            text = "Tout ajouter",
                            modifier = Modifier.weight(1f),
                            height = 56.dp,
                            fontSize = 16.sp,
                            enabled = !state.generating
                        ) {
                            // Closes right away and lets the likes land behind: twenty rows animating
                            // out one by one is just the network being watched.
                            val added = discoveries.addAll()
                            Toast.makeText(context, "$added titres ajoutés aux favoris", Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                        MyButton(
                            text = "Tout ignorer",
                            modifier = Modifier.weight(1f),
                            height = 56.dp,
                            fontSize = 16.sp,
                            enabled = !state.generating
                        ) {
                            discoveries.dismissAll()
                            onBack()
                        }
                    }
                }
                itemsIndexed(tracks, key = { _, item -> item.track.sngId }) { index, item ->
                    TrackRow(
                        track = item.track,
                        onClick = { scope.launch { repo.playTracks(tracks.map { it.track }, index) } },
                        showActions = true,
                        isFavorite = false,
                        isPlaying = playerState.hasItem && playerState.sngId == item.track.sngId,
                        note = item.note(),
                        onToggleFavorite = { discoveries.add(item) },
                        onAddToQueue = {
                            scope.launch {
                                Toast.makeText(context, addToQueueMessage(repo, item.track), Toast.LENGTH_SHORT).show()
                            }
                        },
                        onAddToBestPepites = {
                            scope.launch {
                                Toast.makeText(context, addToBestPepitesMessage(repo, item.track), Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDismiss = { discoveries.dismiss(item) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
