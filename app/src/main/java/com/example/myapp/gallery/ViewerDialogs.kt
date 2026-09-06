package com.example.myapp.gallery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.myapp.AppDialog
import com.example.myapp.ShowAlertDialog
import com.example.myapp.shareUrisIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The dialogs the full screen viewer opens on the current item: sharing it, renaming it, and
// moving it to another album. MoveDialog is also used by the album grid's batch move.

/** Which of the viewer's dialogs is open. Never more than one, so the viewer holds a single state. */
enum class ViewerDialog { Rename, Move, Share, Info }

/**
 * The viewer's dialog run, kept out of GalleryViewerScreen: it needs nothing from that screen
 * beyond the item in view and where it came from. [onMoved] is what leaves the viewer once the
 * item is no longer in the album being shown.
 */
@Composable
fun ViewerDialogs(
    dialog: ViewerDialog?,
    item: MediaItem,
    source: ViewerSource,
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = LocalMediaConsent.current
    when (dialog) {
        null -> return
        ViewerDialog.Rename -> RenameDialog(
            item = item,
            onDismiss = onDismiss,
            onConfirm = { newName ->
                onDismiss()
                scope.launch {
                    if (performRename(context, item, newName, requestConsent)) GalleryRefresh.bump()
                    else onError("Renommage impossible")
                }
            }
        )
        ViewerDialog.Move -> MoveDialog(
            // A single-item viewer has no album of its own to exclude, so it goes by the item's.
            currentBucketId = when (source) {
                is ViewerSource.Album -> source.bucketId
                is ViewerSource.Single -> item.bucketId
                else -> -1L
            },
            onDismiss = onDismiss,
            onConfirm = { targetRelativePath ->
                onDismiss()
                scope.launch {
                    if (performMoveBatch(context, listOf(item), targetRelativePath, requestConsent)) {
                        GalleryRefresh.bump()
                        onMoved()
                    } else {
                        onError("Déplacement impossible")
                    }
                }
            }
        )
        ViewerDialog.Share -> ShareDialog(items = listOf(item), onDismiss = onDismiss, onError = onError)
        ViewerDialog.Info -> InfoDialog(item = item, onDismiss = onDismiss)
    }
}

// The only apps Valentin ever shares photos/videos to. Restricting to these instead of the
// full system share sheet keeps the picker to a single tap on the app that's actually wanted.
// Instagram takes one intent per destination, so its three land here as three targets of their
// own rather than behind a second step: one tap always shares, never just opens another row.
private data class ShareTarget(
    val packageName: String,
    val label: String,
    val instagram: InstagramDestination? = null
)

private const val INSTAGRAM_PACKAGE = "com.instagram.android"

// Where inside Instagram a share goes. A plain ACTION_SEND only ever reaches Direct, so the story
// needs its own intent and the feed its own activity.
private enum class InstagramDestination(val singleItemOnly: Boolean) {
    Story(true),
    Direct(true),
    Feed(false)
}

private val shareTargets = listOf(
    ShareTarget("com.whatsapp", "WhatsApp"),
    ShareTarget("com.beeper.android", "Beeper"),
    ShareTarget("com.facebook.orca", "Messenger"),
    ShareTarget(INSTAGRAM_PACKAGE, "Story", InstagramDestination.Story),
    ShareTarget(INSTAGRAM_PACKAGE, "Message", InstagramDestination.Direct),
    ShareTarget(INSTAGRAM_PACKAGE, "Publication", InstagramDestination.Feed)
)

@Composable
fun ShareDialog(items: List<MediaItem>, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val installedTargets = remember(items.size) {
        shareTargets.filter { target ->
            val fitsBatch = items.size == 1 || target.instagram?.singleItemOnly != true
            fitsBatch && try {
                packageManager.getApplicationInfo(target.packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    AppDialog(onDismiss = onDismiss) {
        Text(
            if (items.size > 1) "Partager ${items.size} fichiers" else "Partager",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(16.dp))
        if (installedTargets.isEmpty()) {
            Text("Aucune application compatible installée")
        } else {
            // Three per line: six icons on one row would be too narrow to hit.
            installedTargets.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { target ->
                        ShareTargetIcon(
                            target = target,
                            onClick = {
                                onDismiss()
                                try {
                                    if (target.instagram != null) {
                                        shareToInstagram(context, items, target.instagram)
                                    } else {
                                        shareItemsTo(context, items, target.packageName)
                                    }
                                } catch (e: Exception) {
                                    // Not just ActivityNotFoundException: naming Instagram's own
                                    // handler activity throws a SecurityException the day it stops
                                    // being exported, and that must not take the app down.
                                    onError("Partage impossible")
                                }
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    }
}

@Composable
private fun ShareTargetIcon(target: ShareTarget, onClick: () -> Unit) {
    val context = LocalContext.current
    val appIcon = remember(target.packageName) {
        try {
            context.packageManager.getApplicationIcon(target.packageName).toBitmap().asImageBitmap()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = target.label, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(target.label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Instagram takes one intent per destination. Direct is what a plain ACTION_SEND lands on, the story
 * has its own action, and the feed needs its own handler activity, which is named here rather than
 * resolved: without it the feed share falls back to the Direct picker. If that class ever moves, the
 * plain send is still what happens.
 */
private fun shareToInstagram(context: Context, items: List<MediaItem>, destination: InstagramDestination) {
    if (items.isEmpty()) return
    val mimeType = shareMimeType(items)
    when (destination) {
        InstagramDestination.Story -> {
            val intent = Intent("com.instagram.share.ADD_TO_STORY")
                .setDataAndType(items.first().uri, mimeType)
                .setPackage(INSTAGRAM_PACKAGE)
                .putExtra("source_application", context.packageName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }
        InstagramDestination.Direct -> shareItemsTo(context, items.take(1), INSTAGRAM_PACKAGE)
        InstagramDestination.Feed -> {
            val intent = shareUrisIntent(items.map { it.uri }, mimeType)
                .setClassName(INSTAGRAM_PACKAGE, "com.instagram.share.handleractivity.ShareHandlerActivity")
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                shareItemsTo(context, items, INSTAGRAM_PACKAGE)
            }
        }
    }
}

private fun shareItemsTo(context: Context, items: List<MediaItem>, packageName: String) {
    if (items.isEmpty()) return
    val intent = shareUrisIntent(items.map { it.uri }, shareMimeType(items)).setPackage(packageName)
    context.startActivity(intent)
}

// One mime type has to cover the whole batch: the exact type when they all share it (a blank one
// makes that impossible), the generic image/video type when only the kind matches, and */* for a
// mix of photos and videos.
private fun shareMimeType(items: List<MediaItem>): String {
    items.map { it.mimeType }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    val kinds = items.map { it.type }.distinct()
    return when {
        kinds.size > 1 -> "*/*"
        kinds.first() == MediaType.VIDEO -> "video/*"
        else -> "image/*"
    }
}

@Composable
fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    // dateTaken is millis, dateAdded is seconds (MediaStore convention); dateTaken is 0 when the
    // file carries no capture metadata (e.g. a screenshot or a download), so fall back to when it
    // landed on the device instead of showing nothing.
    val takenAtMillis = if (item.dateTaken > 0) item.dateTaken else item.dateAdded * 1000
    val formatter = remember { SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRENCH) }
    val formatted = remember(takenAtMillis) { formatter.format(Date(takenAtMillis)) }

    AppDialog(onDismiss = onDismiss) {
        Text("Infos", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Text("Prise le $formatted")
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    }
}

@Composable
fun RenameDialog(item: MediaItem, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val dotIndex = item.displayName.lastIndexOf('.')
    val baseName = if (dotIndex > 0) item.displayName.substring(0, dotIndex) else item.displayName
    val extension = if (dotIndex > 0) item.displayName.substring(dotIndex) else ""
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(baseName, selection = TextRange(0, baseName.length)))
    }
    val focusRequester = remember { FocusRequester() }

    ShowAlertDialog(
        onDismiss = onDismiss,
        title = "Renommer",
        textContent = {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                singleLine = true,
                suffix = { if (extension.isNotEmpty()) Text(extension) },
                modifier = Modifier.focusRequester(focusRequester)
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        },
        onCancel = onDismiss,
        onConfirm = {
            val trimmed = textFieldValue.text.trim()
            if (trimmed.isNotEmpty()) onConfirm("$trimmed$extension")
        }
    )
}

@Composable
fun MoveDialog(currentBucketId: Long, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var newFolderName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        albums = withContext(Dispatchers.IO) {
            queryAlbums(context).filter { it.bucketId != currentBucketId }
        }
    }

    AppDialog(onDismiss = onDismiss) {
        Text("Déplacer vers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = newFolderName,
            onValueChange = { newFolderName = it },
            placeholder = { Text("Nouveau dossier") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (newFolderName.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConfirm("Pictures/${newFolderName.trim()}/") }
                    .padding(vertical = 10.dp)
            ) {
                Text("Créer et déplacer dans « ${newFolderName.trim()} »")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        val currentAlbums = albums
        when {
            currentAlbums == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            currentAlbums.isEmpty() -> Text("Aucun autre album", modifier = Modifier.padding(vertical = 12.dp))
            else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(currentAlbums, key = { it.bucketId }) { album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(album.relativePath) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(album.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    }
}
