package com.example.myapp.deezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapp.formatPlaybackTime
import com.example.myapp.userMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

/** The full player's title tapped: release date, credits, album, label and the rest of the catalog's facts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(repo: DeezerRepository, sngId: String, onDismiss: () -> Unit) {
    val details by produceState<Result<DeezerTrackDetails>?>(null, sngId) {
        value = try {
            Result.success(repo.trackDetails(sngId))
        } catch (e: Exception) {
            Result.failure<DeezerTrackDetails>(IllegalStateException(userMessage(e), e))
        }
    }
    val favorites by repo.favorites.collectAsState()
    val addedAtSec = favorites?.firstOrNull { it.sngId == sngId }?.addedAtSec ?: 0L

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            val result = details
            when {
                result == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
                result.isFailure -> Text(
                    result.exceptionOrNull()?.message.orEmpty(),
                    color = MaterialTheme.colorScheme.error
                )
                else -> TrackInfo(result.getOrThrow(), addedAtSec)
            }
        }
    }
}

@Composable
private fun TrackInfo(d: DeezerTrackDetails, addedAtSec: Long) {
    Text(d.title, style = MaterialTheme.typography.titleLarge)
    Text(
        d.artists.joinToString(", "),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoRow("Album", d.album.ifBlank { null })
        InfoRow("Sortie", d.releaseDate?.let { runCatching { LocalDate.parse(it).format(dateFormat) }.getOrDefault(it) })
        InfoRow("Durée", d.durationSec.takeIf { it > 0 }?.let { formatPlaybackTime(it * 1000L) })
        InfoRow("Piste", d.trackPosition?.let { pos -> if ((d.diskNumber ?: 1) > 1) "$pos (disque ${d.diskNumber})" else "$pos" })
        InfoRow("BPM", d.bpm?.let { "%.0f".format(it) })
        InfoRow("Genre", d.genres.joinToString(", ").ifBlank { null })
        InfoRow("Label", d.label)
        InfoRow("Explicite", if (d.explicit) "Oui" else null)
        InfoRow(
            "Aimé le",
            addedAtSec.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(dateFormat) }
        )
        InfoRow("ISRC", d.isrc)
    }
}

// A fact the catalog doesn't have is left out rather than shown empty.
@Composable
private fun InfoRow(label: String, value: String?) {
    if (value == null) return
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
