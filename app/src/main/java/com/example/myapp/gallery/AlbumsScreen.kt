package com.example.myapp.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryAlbumsScreen(
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPinned: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasReadMediaPermission(context)) }
    var hasAllFiles by remember { mutableStateOf(hasAllFilesAccess()) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var trashCount by remember { mutableStateOf(0) }
    val refreshVersion by GalleryRefresh.version.collectAsState()
    val pinDao = remember { AppDatabase.get(context).pinnedMediaItemDao() }
    val pinnedRows by pinDao.observePinned().collectAsState(initial = emptyList())
    var heroItem by remember { mutableStateOf<MediaItem?>(null) }
    var pinnedCount by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermission = result.values.all { it } }

    val requestAllFiles = rememberAllFilesAccessRequester { hasAllFiles = hasAllFilesAccess() }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(readMediaPermissions())
    }

    LaunchedEffect(hasPermission, refreshVersion) {
        if (hasPermission) {
            albums = withContext(Dispatchers.IO) { queryAlbums(context) }
            trashCount = withContext(Dispatchers.IO) { queryTrashedItems(context).size }
        }
    }

    LaunchedEffect(hasPermission, pinnedRows) {
        if (hasPermission) {
            val resolved = resolvedPinnedMediaItems(context, pinnedRows)
            heroItem = resolved.firstOrNull()
            pinnedCount = resolved.size
        }
    }

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Galerie", onBack = onBack, modifier = Modifier.padding(16.dp))

        if (hasPermission && !hasAllFiles) {
            AllFilesAccessBanner(onGrant = requestAllFiles)
        }

        heroItem?.let { item ->
            PinnedHeroCard(
                item = item,
                pinnedCount = pinnedCount,
                onClick = onOpenPinned,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
            )
        }

        when {
            !hasPermission -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "L'accès aux photos et vidéos est nécessaire pour afficher la galerie.",
                    textAlign = TextAlign.Center
                )
            }
            albums == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            albums!!.isEmpty() && trashCount == 0 -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Aucune photo ou vidéo trouvée")
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(albums!!, key = { it.bucketId }) { album ->
                    AlbumCard(album = album, onClick = { onOpenAlbum(album.bucketId) })
                }
                // Last, after every album: deleting a photo shouldn't push the albums down.
                if (trashCount > 0) {
                    item(key = "trash") { TrashCard(itemCount = trashCount, onClick = onOpenTrash) }
                }
            }
        }
    }
}

@Composable
private fun AllFilesAccessBanner(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
    ) {
        Text(
            "Autorise l'accès complet aux fichiers pour supprimer et déplacer tes photos sans " +
                "la confirmation Android à chaque fois.",
            style = MaterialTheme.typography.bodyMedium
        )
        MyButton(
            text = "Autoriser",
            onClick = onGrant,
            height = 56.dp,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// Sits where an album cover would, first in the grid, and only while the trash holds something.
@Composable
private fun TrashCard(itemCount: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Corbeille",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "$itemCount élément${if (itemCount > 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

// The big hero picture at the top: the first pinned picture (or whichever was manually promoted),
// sized to be quickly tappable without dominating the album list below it.
@Composable
private fun PinnedHeroCard(item: MediaItem, pinnedCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        GalleryAsyncImage(
            uri = item.uri,
            dateModified = item.dateModified,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (pinnedCount > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(pinnedCount.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        GalleryAsyncImage(
            uri = album.coverUri,
            dateModified = album.coverDateModified,
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        )
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "${album.itemCount} élément${if (album.itemCount > 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}
