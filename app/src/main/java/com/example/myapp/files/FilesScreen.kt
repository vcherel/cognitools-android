package com.example.myapp.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.gallery.hasAllFilesAccess
import com.example.myapp.gallery.rememberAllFilesAccessRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** What a Couper/Copier left waiting for a Coller in some other folder. */
private data class Clipboard(val files: List<File>, val move: Boolean)

/**
 * The file explorer. One folder at a time, opening on Téléchargements, with a breadcrumb up to the
 * storage root. Long pressing a row starts a selection, which the action bar then shares, renames,
 * copies, moves or deletes. Everything it does to the disk lives in FileOps.kt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    val requestAccess = rememberAllFilesAccessRequester { hasAccess = hasAllFilesAccess() }

    var currentDir by remember { mutableStateOf(if (downloadsDir.isDirectory) downloadsDir else storageRoot) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clipboard by remember { mutableStateOf<Clipboard?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Which app opens which extension, chosen the first time a file of that kind was opened.
    val defaults by FileDefaults.flow(context).collectAsState(initial = emptyMap())
    fun defaultFor(file: File): String? = defaults[file.extension.lowercase(Locale.ROOT)]

    var showMenu by remember { mutableStateOf(false) }
    var showDefaults by remember { mutableStateOf(false) }
    var toRename by remember { mutableStateOf<FileEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(currentDir, refreshKey, hasAccess) {
        entries = null
        selected = emptySet()
        entries = if (hasAccess) withContext(Dispatchers.IO) { listFolder(currentDir) } else emptyList()
    }

    val selectedEntries = entries.orEmpty().filter { it.path in selected }

    fun reload() { refreshKey++ }

    fun runOnFiles(action: suspend () -> String) {
        busy = true
        scope.launch {
            val message = action()
            busy = false
            selected = emptySet()
            reload()
            if (message.isNotEmpty()) AppSnackbar.show(message)
        }
    }

    val goUp: () -> Unit = { parentOf(currentDir)?.let { currentDir = it } }
    val handleBack: () -> Unit = {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            parentOf(currentDir) != null -> goUp()
            else -> onBack()
        }
    }
    BackHandler(enabled = selected.isNotEmpty() || parentOf(currentDir) != null) { handleBack() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Fichiers", onBack = handleBack) {
            Box(Modifier.weight(1f))
            IconButton(onClick = { showNewFolder = true }, enabled = hasAccess) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Nouveau dossier")
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                }
                val singleFile = selectedEntries.singleOrNull()?.takeIf { !it.isDirectory }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Ouvrir avec…") },
                        enabled = singleFile != null,
                        onClick = {
                            showMenu = false
                            val file = singleFile ?: return@DropdownMenuItem
                            selected = emptySet()
                            if (!openFile(context, file.file, forceChooser = true)) {
                                AppSnackbar.show("Aucune application pour ce fichier")
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Applications par défaut") },
                        onClick = {
                            showMenu = false
                            showDefaults = true
                        }
                    )
                }
            }
        }

        if (!hasAccess) {
            MissingAccess(onRequest = requestAccess)
            return@Column
        }

        Breadcrumb(dir = currentDir, onOpen = { currentDir = it })

        if (selected.isNotEmpty()) {
            SelectionBar(
                count = selected.size,
                onClear = { selected = emptySet() },
                onSelectAll = { selected = entries.orEmpty().map { it.path }.toSet() },
                onShare = {
                    val files = selectedEntries.filter { !it.isDirectory }.map { it.file }
                    if (files.isEmpty()) AppSnackbar.show("Rien à partager dans la sélection")
                    else if (!shareFiles(context, files)) AppSnackbar.show("Partage impossible")
                },
                onRename = { toRename = selectedEntries.singleOrNull() },
                onCut = {
                    clipboard = Clipboard(selectedEntries.map { it.file }, move = true)
                    selected = emptySet()
                },
                onCopy = {
                    clipboard = Clipboard(selectedEntries.map { it.file }, move = false)
                    selected = emptySet()
                },
                onDelete = { confirmDelete = true },
                renameEnabled = selected.size == 1
            )
        }

        clipboard?.let { pending ->
            ClipboardBar(
                clipboard = pending,
                onCancel = { clipboard = null },
                onPaste = {
                    val destination = currentDir
                    runOnFiles {
                        val done = withContext(Dispatchers.IO) {
                            if (pending.move) moveEntries(pending.files, destination)
                            else copyEntries(pending.files, destination)
                        }
                        clipboard = null
                        val verb = if (pending.move) "déplacé" else "copié"
                        val plural = if (done > 1) "s" else ""
                        if (done == pending.files.size) "$done élément$plural $verb$plural"
                        else "$done / ${pending.files.size} élément$plural $verb$plural"
                    }
                }
            )
        }

        if (busy) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }

        val list = entries
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Dossier vide",
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        selected = entry.path in selected,
                        selectionMode = selected.isNotEmpty(),
                        onClick = {
                            when {
                                selected.isNotEmpty() ->
                                    selected = if (entry.path in selected) selected - entry.path
                                    else selected + entry.path
                                entry.isDirectory -> currentDir = entry.file
                                !openFile(context, entry.file, defaultFor(entry.file)) ->
                                    AppSnackbar.show("Aucune application pour ce fichier")
                            }
                        },
                        onLongClick = {
                            selected = if (entry.path in selected) selected - entry.path
                            else selected + entry.path
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    toRename?.let { entry ->
        NameDialog(
            title = "Renommer",
            initialName = entry.name,
            confirmText = "Renommer",
            onDismiss = { toRename = null },
            onConfirm = { newName ->
                toRename = null
                runOnFiles {
                    val ok = withContext(Dispatchers.IO) { renameEntry(entry.file, newName) }
                    if (ok) "" else "Renommage impossible"
                }
            }
        )
    }

    if (showDefaults) {
        DefaultAppsDialog(
            defaults = defaults,
            onForget = { extension -> scope.launch { FileDefaults.forget(context, extension) } },
            onDismiss = { showDefaults = false }
        )
    }

    if (showNewFolder) {
        NameDialog(
            title = "Nouveau dossier",
            initialName = "",
            confirmText = "Créer",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                val parent = currentDir
                runOnFiles {
                    val ok = withContext(Dispatchers.IO) { createFolder(parent, name) }
                    if (ok) "" else "Création impossible"
                }
            }
        )
    }

    if (confirmDelete) {
        val count = selected.size
        ShowAlertDialog(
            onDismiss = { confirmDelete = false },
            title = if (count == 1) "Supprimer « ${selectedEntries.firstOrNull()?.name.orEmpty()} » ?"
            else "Supprimer $count éléments ?",
            textContent = {
                Text("La suppression est définitive, les dossiers sont supprimés avec leur contenu.")
            },
            confirmText = "Supprimer",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val files = selectedEntries.map { it.file }
                runOnFiles {
                    val done = withContext(Dispatchers.IO) { deleteEntries(files) }
                    val plural = if (done > 1) "s" else ""
                    if (done == files.size) "$done élément$plural supprimé$plural"
                    else "$done / ${files.size} élément$plural supprimé$plural"
                }
            }
        )
    }
}

@Composable
private fun MissingAccess(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "L'explorateur a besoin de l'accès à tous les fichiers pour lire le stockage.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        MyButton(text = "Autoriser", height = 72.dp, onClick = onRequest)
    }
}

@Composable
private fun Breadcrumb(dir: File, onOpen: (File) -> Unit) {
    val crumbs = breadcrumb(dir)
    val scrollState = rememberScrollState()
    // The deepest folder is the interesting end, so a long path shows its tail first.
    LaunchedEffect(dir) { scrollState.scrollTo(scrollState.maxValue) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { index, (label, folder) ->
            if (index > 0) {
                Text(
                    text = " › ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val last = index == crumbs.lastIndex
            TextButton(onClick = { if (!last) onOpen(folder) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (last) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    renameEnabled: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "Annuler la sélection")
        }
        Text("$count", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.weight(1f))
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Filled.SelectAll, contentDescription = "Tout sélectionner")
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = "Partager")
        }
        IconButton(onClick = onRename, enabled = renameEnabled) {
            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Renommer")
        }
        IconButton(onClick = onCut) {
            Icon(Icons.Filled.ContentCut, contentDescription = "Déplacer")
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copier")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ClipboardBar(clipboard: Clipboard, onPaste: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val verb = if (clipboard.move) "à déplacer" else "à copier"
        Text(
            text = "${clipboard.files.size} $verb",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Box(Modifier.weight(1f))
        TextButton(onClick = onPaste) { Text("Coller ici") }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Abandonner")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = iconFor(entry),
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitleFor(entry),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private fun subtitleFor(entry: FileEntry): String {
    val date = formatFileDate(entry.lastModified)
    if (entry.isDirectory) {
        val count = entry.childCount
        val label = if (count == 1) "1 élément" else "$count éléments"
        return if (date.isEmpty()) label else "$label · $date"
    }
    val size = formatFileSize(entry.size)
    return if (date.isEmpty()) size else "$size · $date"
}

private fun iconFor(entry: FileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Filled.Folder
    return when (entry.file.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Icons.Filled.Image
        "mp4", "mkv", "avi", "mov", "webm", "3gp" -> Icons.Filled.Movie
        "mp3", "flac", "wav", "ogg", "m4a", "opus" -> Icons.Filled.Audiotrack
        "pdf" -> Icons.Filled.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz", "apk" -> Icons.Filled.FolderZip
        "txt", "md", "json", "xml", "csv", "log", "doc", "docx", "epub" -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

/** The saved "this extension opens with that app" choices, each removable. */
@Composable
private fun DefaultAppsDialog(
    defaults: Map<String, String>,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AppDialog(onDismiss = onDismiss) {
        Text("Applications par défaut", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        if (defaults.isEmpty()) {
            Text(
                "Aucune pour l'instant : l'application choisie à la première ouverture d'un type de fichier est retenue ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                defaults.entries.sortedBy { it.key }.forEach { (extension, component) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ".$extension",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            text = appLabelFor(context, component),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onForget(extension) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Oublier ce choix")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        MyButton(
            text = "Fermer",
            modifier = Modifier.fillMaxWidth().height(50.dp),
            fontSize = 14.sp,
            onClick = onDismiss
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AppDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MyButton(
                text = "Annuler",
                modifier = Modifier.weight(1f).height(50.dp),
                fontSize = 14.sp,
                onClick = onDismiss
            )
            MyButton(
                text = confirmText,
                modifier = Modifier.weight(1f).height(50.dp),
                fontSize = 14.sp,
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) }
            )
        }
    }
}
