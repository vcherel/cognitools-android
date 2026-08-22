package com.example.myapp.news

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
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
 * One article line: the text, and the illustration on the right when the feed carried one. A read
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
                Text(
                    " · ${newsRelativeTime(article.publishedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            Text(
                article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (article.summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    article.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

fun shareArticle(context: Context, article: NewsArticle) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, article.title)
        putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.link}")
    }
    context.startActivity(Intent.createChooser(intent, "Partager l'article"))
}
