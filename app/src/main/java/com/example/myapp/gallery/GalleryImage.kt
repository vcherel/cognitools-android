package com.example.myapp.gallery

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
