package com.example.myapp.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.launch

/** The books already imported, newest read first. Tapping one opens it where it was left. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookLibraryScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).bookDao() }
    val books by dao.observeBooks().collectAsState(initial = emptyList())
    var importing by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            importEpub(context, uri)
                .onSuccess { AppSnackbar.show("« ${it.title} » ajouté") }
                .onFailure { AppSnackbar.show(it.message ?: "Import impossible") }
            importing = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Lecture", onBack = onBack) {
            Box(Modifier.weight(1f))
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter un livre")
            }
        }

        // Discreet inline line rather than a screen that hides the shelf while a file copies.
        if (importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (books.isEmpty() && !importing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Aucun livre pour l'instant.\nAjoutez un fichier epub avec le +.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(books, key = { it.id }) { book ->
                Column(
                    Modifier.combinedClickable(
                        onClick = { onOpenBook(book.id) },
                        onLongClick = { toDelete = book }
                    )
                ) {
                    BookCover(book)
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Chapitre ${book.chapterIndex + 1} / ${book.chapterCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // Deleting a book removes the only copy of the file, so this one still asks.
    toDelete?.let { book ->
        ShowAlertDialog(
            onDismiss = { toDelete = null },
            title = "Supprimer « ${book.title} » ?",
            textContent = { Text("Le fichier et la progression sont perdus.") },
            confirmText = "Supprimer",
            onConfirm = {
                toDelete = null
                scope.launch { deleteBook(context, book) }
            },
            onCancel = { toDelete = null }
        )
    }
}

@Composable
private fun BookCover(book: Book) {
    val context = LocalContext.current
    val cover = remember(book.id, book.coverFileName) { coverFile(context, book)?.takeIf { it.exists() } }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.66f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            AsyncImage(
                model = Uri.fromFile(cover),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
