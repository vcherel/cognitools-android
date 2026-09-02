package com.example.myapp.news

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One article line: the title in full, and the illustration on the right when the feed carried one. A read
 * article is dimmed rather than hidden, and a long press toggles that state back and forth.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewsArticleRow(
    article: NewsArticle,
    isRead: Boolean,
    isSaved: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).alpha(if (isRead) 0.5f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    article.source,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                // An archive card with no picture has no date to read off, and then the outlet
                // stands alone rather than trailing an empty separator.
                newsRelativeTime(article.publishedAt).takeIf { it.isNotBlank() }?.let { when_ ->
                    Text(
                        " · $when_",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSaved) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = "Sauvegardé",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            // The whole title, never cut: the list is scanned by title, and the feed's first
            // sentences underneath only pushed the next article off the screen.
            Text(
                article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold
            )
        }
        article.imageUrl?.let { url ->
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(96.dp, 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .alpha(if (isRead) 0.5f else 1f)
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                )
            }
        }
    }
}

/**
 * The card offering to pick the last unfinished article back up, drawn above the list. Only one is
 * ever shown: the point is one tap back into what was being read, not a second reading list.
 */
@Composable
fun NewsResumeCard(progress: NewsProgress, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Reprendre la lecture",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                progress.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${progress.source} \u00b7 ${(progress.ratio * 100).toInt()} %",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                LinearProgressIndicator(
                    progress = { progress.ratio },
                    drawStopIndicator = {},
                    modifier = Modifier.weight(1f).height(4.dp)
                )
            }
        }
    }
}

private val newsDateFormat = SimpleDateFormat("d MMM", Locale.FRENCH)
private val newsFullDateFormat = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRENCH)

/** "il y a 20 min", "il y a 3 h", "hier", then the date. An unknown date says nothing. */
fun newsRelativeTime(publishedAt: Long): String {
    if (publishedAt <= 0) return ""
    val minutes = (System.currentTimeMillis() - publishedAt) / 60_000
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${minutes / 60} h"
        minutes < 48 * 60 -> "hier"
        else -> newsDateFormat.format(publishedAt)
    }
}

fun newsFullDate(publishedAt: Long): String =
    if (publishedAt <= 0) "" else newsFullDateFormat.format(publishedAt)

fun openInBrowser(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
