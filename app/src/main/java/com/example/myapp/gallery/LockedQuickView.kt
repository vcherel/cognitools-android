package com.example.myapp.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Shown instead of the full app when a picture/video is opened while the phone is still locked
// (e.g. tapping the just-taken-photo thumbnail from the camera's lock screen quick launch).
// Deliberately minimal: view only, no share/crop/rename/delete/navigation, so nothing beyond this
// one item is ever reachable without a real unlock. MainActivity draws this over the keyguard via
// showWhenLocked/turnScreenOn and never requests a keyguard dismissal for it.
@Composable
fun LockedQuickView(item: MediaItem, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (item.type) {
            MediaType.IMAGE -> ZoomableImage(
                item = item,
                onZoomChanged = {},
                onToggleChrome = {}
            )
            MediaType.VIDEO -> VideoPlayer(
                uri = item.uri,
                chromeVisible = true,
                onChromeVisibleChange = {},
                onZoomChanged = {}
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
        }
    }
}
