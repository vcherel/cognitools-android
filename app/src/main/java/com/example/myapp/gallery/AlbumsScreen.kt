package com.example.myapp.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryAlbumsScreen(onBack: () -> Unit, onOpenAlbum: (Long) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasReadMediaPermission(context)) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    val refreshVersion by GalleryRefresh.version.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermission = result.values.all { it } }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(readMediaPermissions())
    }

    LaunchedEffect(hasPermission, refreshVersion) {
        if (hasPermission) {
            albums = withContext(Dispatchers.IO) { queryAlbums(context) }
        }
    }

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Galerie", onBack = onBack, modifier = Modifier.padding(16.dp))

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
            albums!!.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
