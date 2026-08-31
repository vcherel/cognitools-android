package com.example.myapp.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

// A plain content:// uri never changes even after an in-place crop/trim overwrite, so Coil would
// otherwise keep serving the pre-edit bytes from its cache. Folding dateModified into the cache
// key makes an edit look like a brand new image to Coil.
@Composable
fun GalleryAsyncImage(
    uri: Uri,
    dateModified: Long,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheKey = "$uri#$dateModified"
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
    )
}

/**
 * Shared grid cell: the thumbnail, a video play-icon overlay, an optional caller-provided overlay
 * (e.g. the trash's expiry badge), and, when [showSelectionIndicator] is on, the selection tint
 * and checkmark/circle used by both the album grid and the trash grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableMediaThumbnail(
    item: MediaItem,
    selected: Boolean,
    showSelectionIndicator: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    extraOverlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        GalleryAsyncImage(
            uri = item.uri,
            dateModified = item.dateModified,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (item.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        extraOverlay()
        if (showSelectionIndicator) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                )
            }
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.25f), CircleShape)
            )
        }
    }
}
