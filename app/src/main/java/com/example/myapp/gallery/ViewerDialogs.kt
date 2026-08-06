package com.example.myapp.gallery

import android.content.ActivityNotFoundException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The dialogs the full screen viewer opens on the current item: sharing it, renaming it, and
// moving it to another album. MoveDialog is also used by the album grid's batch move.

// The only apps Valentin ever shares photos/videos to. Restricting to these instead of the
// full system share sheet keeps the picker to a single tap on the app that's actually wanted.
private data class ShareTarget(val packageName: String, val label: String)

private val shareTargets = listOf(
    ShareTarget("com.whatsapp", "WhatsApp"),
    ShareTarget("com.beeper.android", "Beeper"),
    ShareTarget("com.facebook.orca", "Messenger"),
    ShareTarget("com.instagram.android", "Instagram")
)

@Composable
fun ShareDialog(item: MediaItem, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val installedTargets = remember {
        shareTargets.filter { target ->
            try {
                packageManager.getApplicationInfo(target.packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    AppDialog(onDismiss = onDismiss) {
        Text("Partager", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (installedTargets.isEmpty()) {
            Text("Aucune application compatible installée")
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                installedTargets.forEach { target ->
                    ShareTargetIcon(
                        target = target,
                        onClick = {
                            onDismiss()
                            try {
                                shareItemTo(context, item, target.packageName)
                            } catch (e: ActivityNotFoundException) {
                                onError("Partage impossible")
                            }
                        }
                    )
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

private fun shareItemTo(context: Context, item: MediaItem, packageName: String) {
    val mimeType = item.mimeType.ifBlank {
        if (item.type == MediaType.VIDEO) "video/*" else "image/*"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        setPackage(packageName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
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
