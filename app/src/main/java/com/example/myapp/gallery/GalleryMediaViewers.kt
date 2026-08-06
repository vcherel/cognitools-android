package com.example.myapp.gallery

import android.net.Uri
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem as Media3Item

// Pinch-to-zoom scale/offset shared by the photo and video pinch handlers below.
private class PinchZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

@Composable
private fun rememberPinchZoomState(key: Any, onZoomChanged: (Boolean) -> Unit): PinchZoomState {
    val state = remember(key) { PinchZoomState() }
    LaunchedEffect(state.scale) { onZoomChanged(state.scale > 1.01f) }
    return state
}

// Only claims the gesture when it's an actual pinch (two pointers) or a pan while already zoomed
// in. Plain taps (no movement) are left unconsumed so a tap detector layered on top can still
// handle them; a single-finger drag at scale 1 is left for the enclosing HorizontalPager to swipe.
private fun Modifier.pinchZoom(key: Any, zoom: PinchZoomState): Modifier = this
    .pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pinching = event.changes.count { it.pressed } >= 2
                if (pinching || zoom.scale > 1f) {
                    val gestureZoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (gestureZoom != 1f || pan != Offset.Zero) {
                        val newScale = (zoom.scale * gestureZoom).coerceIn(1f, 5f)
                        zoom.scale = newScale
                        zoom.offset = if (newScale <= 1f) Offset.Zero else zoom.offset + pan
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer(
        scaleX = zoom.scale,
        scaleY = zoom.scale,
        translationX = zoom.offset.x,
        translationY = zoom.offset.y
    )

@Composable
internal fun ZoomableImage(
    item: MediaItem,
    onZoomChanged: (Boolean) -> Unit,
    onToggleChrome: () -> Unit
) {
    val zoom = rememberPinchZoomState(item.id, onZoomChanged)

    GalleryAsyncImage(
        uri = item.uri,
        dateModified = item.dateModified,
        contentDescription = item.displayName,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                // Single tap toggles the viewer chrome; double tap zooms to 2.5x centered on the
                // tapped point (or back to fit if already zoomed). Both live in one detector so
                // they don't fight the pinch handler below over consuming the touch.
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { tapPos ->
                        if (zoom.scale > 1f) {
                            zoom.scale = 1f
                            zoom.offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            zoom.offset = (tapPos - center) * (1f - target)
                            zoom.scale = target
                        }
                    }
                )
            }
            .pinchZoom(item.id, zoom)
    )
}

@Composable
internal fun VideoPlayer(
    uri: Uri,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // Locking the phone stops the activity but doesn't stop ExoPlayer on its own,
    // so a video would keep playing audio behind the lock screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The player's own controller is the only tap target on a video page, so it drives the viewer
    // chrome: showing the transport controls also brings up the filename bar and the action row,
    // and hiding them (tap again, or the controller's auto-hide) takes everything away.
    val onVisibilityChange by rememberUpdatedState(onChromeVisibleChange)
    val playerView = remember(uri) {
        PlayerView(context).apply {
            player = exoPlayer
            // Open clean like a photo does, instead of flashing the controls on load.
            controllerAutoShow = false
            setShowSubtitleButton(false)
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    // Track selection and playback speed are useless on a local clip: drop the
                    // gear every time the controller comes back, since it rebuilds its own row.
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    onVisibilityChange(visibility == View.VISIBLE)
                }
            )
        }
    }
    // Keep the controller in sync when the chrome was toggled elsewhere, e.g. swiping in from a
    // photo that had its bars showing.
    LaunchedEffect(playerView, chromeVisible) {
        if (chromeVisible) playerView.showController() else playerView.hideController()
    }

    // Pinch to zoom into the current frame, playing or paused, exactly like the photo viewer, so a
    // plain single-finger tap is left unconsumed and still reaches the player's own controller
    // underneath (play/pause, seek, show/hide controls).
    val zoom = rememberPinchZoomState(uri, onZoomChanged)

    AndroidView(
        factory = { playerView },
        modifier = Modifier
            .fillMaxSize()
            .pinchZoom(uri, zoom)
    )
}
