package com.example.myapp.deezer

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.myapp.notes.appendToDjNote
import com.example.myapp.MediaArt
import com.example.myapp.MediaListRow
import com.example.myapp.MediaRowSubtitle
import com.example.myapp.deezerRepository

/**
 * Shared: one tappable track line. Tap plays. When [showActions] is on, a heart (like/unlike, filled
 * when [isFavorite]) sits on the right, plus a three dot menu with the rest: add to queue, add to or
 * remove from Best pépites, add to a playlist, and, only if [onRemoveFromPlaylist] is provided, remove from the current
 * playlist. [isPlaying] tints the row and adds a playing icon for the track currently loaded in the player.
 * [note] adds a third tinted line under the artist, and [onDismiss] a cross that throws the row away.
 */
@Composable
fun TrackRow(
    track: DeezerTrack,
    onClick: () -> Unit,
    showActions: Boolean = false,
    isFavorite: Boolean = false,
    isPlaying: Boolean = false,
    note: String? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onToggleBestPepites: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Read when the menu opens rather than per row: the whole point of the diamond is that a list of
    // a thousand tracks costs nothing until one of them is actually acted on.
    val repo = LocalContext.current.deezerRepository
    var inPepites by remember(track.sngId) { mutableStateOf(false) }
    LaunchedEffect(menuExpanded, track.sngId) {
        if (!menuExpanded) return@LaunchedEffect
        runCatching { repo.ensureBestPepitesLoaded() }
        inPepites = repo.bestPepitesContains(track.sngId) == true
    }
    MediaListRow(
        artworkUrl = track.coverUrl(),
        title = track.title,
        isPlaying = isPlaying,
        onClick = onClick,
        below = {
            MediaRowSubtitle(track.artist)
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        },
        trailing = {
            if (!showActions) return@MediaListRow
            IconButton(onClick = { onToggleFavorite?.invoke() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (onAddToQueue != null) {
                        DropdownMenuItem(
                            text = { Text("Ajouter à la file d'attente") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddToQueue() }
                        )
                    }
                    if (onToggleBestPepites != null) {
                        DropdownMenuItem(
                            text = { Text(if (inPepites) "Retirer de Best pépites" else "Ajouter à Best pépites") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Diamond,
                                    contentDescription = null,
                                    tint = if (inPepites) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            },
                            onClick = { menuExpanded = false; onToggleBestPepites() }
                        )
                    }
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Ajouter à une playlist") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddToPlaylist() }
                        )
                    }
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Retirer de la playlist") },
                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                            onClick = { menuExpanded = false; onRemoveFromPlaylist() }
                        )
                    }
                    if (onOpenArtist != null) {
                        DropdownMenuItem(
                            text = { Text("Voir l'artiste") },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                            onClick = { menuExpanded = false; onOpenArtist() }
                        )
                    }
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Ignorer",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/** Shared: runs the "add to queue" action and returns the toast to show. */
suspend fun addToQueueMessage(repo: DeezerRepository, track: DeezerTrack): String =
    runCatching { repo.player.addToQueue(track) }.fold(
        onSuccess = { "Ajouté à la file d'attente" },
        onFailure = { "Échec de l'ajout" }
    )

/**
 * Shared: runs the diamond action and returns the toast to show. Every entry point (row menu, now
 * playing sheet, notification) adds to Best pépites; only a track played from Best pépites itself
 * ([playingFrom]) comes back out of it, so a tap elsewhere never removes one by surprise.
 * Adding one also files it in the DJ note, since putting a track there means wanting it downloaded.
 */
suspend fun bestPepitesMessage(
    context: Context,
    repo: DeezerRepository,
    track: DeezerTrack,
    playingFrom: TrackSource? = null
): String =
    runCatching {
        // Without the membership loaded the track would look absent and be added a second time.
        runCatching { repo.ensureBestPepitesLoaded() }
        if (repo.isBestPepites(playingFrom) && repo.bestPepitesContains(track.sngId) == true) {
            if (repo.removeFromBestPepites(track.sngId)) "Retiré de Best pépites"
            else "Playlist Best pépites introuvable"
        } else when (val result = repo.addToBestPepites(track)) {
            PlaylistAddResult.NO_PLAYLIST -> "Playlist Best pépites introuvable"
            else -> {
                runCatching { appendToDjNote(context, track.artist, track.title) }
                if (result == PlaylistAddResult.ADDED) "Ajouté à Best pépites" else "Déjà dans Best pépites"
            }
        }
    }.getOrElse { "Échec de l'action" }

/** Shared: same for the playlist picked in [PlaylistPickerDialog]. */
suspend fun addToPlaylistMessage(repo: DeezerRepository, playlist: DeezerPlaylist, track: DeezerTrack): String =
    runCatching { repo.addToPlaylist(playlist.id, track) }.fold(
        onSuccess = {
            if (it == PlaylistAddResult.DUPLICATE) "Déjà dans ${playlist.title}" else "Ajouté à ${playlist.title}"
        },
        onFailure = { "Échec de l'ajout" }
    )

/**
 * Shared: a dialog listing all of the owner's playlists so a track can be added to any of them.
 * Loads the playlists on open; [onPick] fires with the chosen playlist.
 */
@Composable
fun PlaylistPickerDialog(
    repo: DeezerRepository,
    onDismiss: () -> Unit,
    onPick: (DeezerPlaylist) -> Unit
) {
    var playlists by remember { mutableStateOf<List<DeezerPlaylist>?>(null) }
    LaunchedEffect(Unit) { runCatching { playlists = repo.fetchPlaylists() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Ajouter à une playlist") },
        text = {
            when (val p = playlists) {
                null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(p) { pl ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(pl) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MediaArt(pl.coverUrl(), Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${pl.trackCount} titres",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
