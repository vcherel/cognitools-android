package com.example.myapp.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.myapp.rememberAppSnackbarHostState

// Shown instead of the full app when a picture/video is opened while the phone is still locked
// (e.g. tapping the just-taken-photo thumbnail from the camera's lock screen quick launch). It is
// the normal viewer, with every tool (share, rename, move, delete, crop, trim, pin, infos), but
// pointed at that one item only: no swiping to its neighbours, no album, no way into the rest of
// the app, so nothing beyond this item is reachable without a real unlock. MainActivity draws it
// over the keyguard via showWhenLocked/turnScreenOn and never requests a keyguard dismissal.
//
// Sharing is the one thing the app can't finish here: the picker shows, but Android puts up the
// unlock prompt itself before handing the file to WhatsApp or Instagram.
@Composable
fun LockedQuickView(item: MediaItem, onClose: () -> Unit) {
    // Both registered here rather than inherited: the nav host that normally owns them (MainScreen)
    // is not composed at all on this path.
    val mediaConsent = rememberIntentSenderRequester()
    val snackbarHostState = rememberAppSnackbarHostState()
    var editing by remember { mutableStateOf<LockedEditor?>(null) }

    CompositionLocalProvider(LocalMediaConsent provides mediaConsent) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            when (val editor = editing) {
                null -> GalleryViewerScreen(
                    source = ViewerSource.Single(item.id),
                    initialItemId = item.id,
                    onBack = onClose,
                    onCrop = { editing = LockedEditor.Crop(it) },
                    onTrim = { editing = LockedEditor.Trim(it) }
                )
                is LockedEditor.Crop -> GalleryCropScreen(
                    itemId = editor.itemId,
                    onBack = { editing = null }
                )
                is LockedEditor.Trim -> GalleryTrimScreen(
                    itemId = editor.itemId,
                    onBack = { editing = null }
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            )
        }
    }
}

// The only two places this view can go: its own crop and trim editors, in place of a nav graph.
private sealed interface LockedEditor {
    val itemId: Long

    data class Crop(override val itemId: Long) : LockedEditor
    data class Trim(override val itemId: Long) : LockedEditor
}
