package com.example.myapp.gallery

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ShowAlertDialog
import com.example.myapp.formatPlaybackTime
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@Composable
fun GalleryTrimScreen(itemId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestConsent = LocalMediaConsent.current

    var item by remember { mutableStateOf<MediaItem?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    var range by remember { mutableStateOf(0f..0f) }
    var exporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        val found = withContext(Dispatchers.IO) { queryMediaItemById(context, itemId) }
        item = found
        if (found != null) {
            val duration = withContext(Dispatchers.IO) { readDurationMs(context, found.uri) }
            durationMs = duration
            range = 0f..duration.toFloat()
        }
    }

    BackHandler { onBack() }

    val currentItem = item

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Durée de la vidéo", onBack = onBack, modifier = Modifier.padding(16.dp))

        if (currentItem == null || durationMs <= 0L) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                VideoPreview(uri = currentItem.uri)
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(range.start.toLong()), style = MaterialTheme.typography.bodyMedium)
                    Text(formatPlaybackTime(range.endInclusive.toLong()), style = MaterialTheme.typography.bodyMedium)
                }
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..durationMs.toFloat()
                )
                Text(
                    "Durée sélectionnée : ${formatPlaybackTime((range.endInclusive - range.start).toLong())}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            MyButton(
                text = if (exporting) "Traitement en cours..." else "Rogner",
                enabled = !exporting && range.endInclusive - range.start >= 200f,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(64.dp)
            ) {
                scope.launch {
                    exporting = true
                    val outputFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
                    val exportOk = trimVideo(context, currentItem.uri, range.start.toLong(), range.endInclusive.toLong(), outputFile)
                    if (!exportOk) {
                        outputFile.delete()
                        exporting = false
                        errorMessage = "Le rognage a échoué"
                        return@launch
                    }
                    val ok = performOverwrite(context, currentItem, requestConsent) { out ->
                        outputFile.inputStream().use { it.copyTo(out) }
                    }
                    outputFile.delete()
                    exporting = false
                    if (ok) onBack() else errorMessage = "Enregistrement impossible"
                }
            }
        }
    }

    errorMessage?.let { message ->
        ShowAlertDialog(
            onDismiss = { errorMessage = null },
            title = message,
            onConfirm = { errorMessage = null }
        )
    }
}

@Composable
private fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                player = exoPlayer
                setShowSubtitleButton(false)
                setControllerVisibilityListener(
                    androidx.media3.ui.PlayerView.ControllerVisibilityListener {
                        // Track selection and playback speed are useless when trimming a local
                        // clip: drop the gear every time the controller comes back, matching the
                        // main viewer's video player.
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility =
                            android.view.View.GONE
                    }
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun readDurationMs(context: android.content.Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        retriever.release()
    }
}

private suspend fun trimVideo(context: android.content.Context, uri: Uri, startMs: Long, endMs: Long, outputFile: File): Boolean =
    suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            .build()

        val mediaItem = Media3Item.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                Media3Item.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        cont.invokeOnCancellation { transformer.cancel() }
        transformer.start(mediaItem, outputFile.absolutePath)
    }
