package com.example.myapp.news

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.newsRepository
import kotlinx.coroutines.launch

/**
 * The starred articles, newest first. Their text was kept when they were saved, so they stay
 * readable long after the feeds have moved on. A long press unsaves, with an undo.
 */
@Composable
fun NewsSavedScreen(onBack: () -> Unit, onOpenArticle: (String) -> Unit) {
    val context = LocalContext.current
    val repo = context.newsRepository
    val scope = rememberCoroutineScope()
    val saved by repo.saved.collectAsState(initial = emptyList())
    val readLinks by repo.readLinks.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Sauvegardés",
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleLarge,
            titleWeight = true
        )

        if (saved.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aucun article sauvegardé.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(saved, key = { it.link }) { row ->
                    NewsArticleRow(
                        article = row.toArticle(),
                        isRead = row.link in readLinks,
                        isSaved = true,
                        onClick = { onOpenArticle(row.link) },
                        onLongClick = {
                            scope.launch {
                                repo.unsave(row.link)
                                AppSnackbar.show(
                                    message = "Retiré des sauvegardés",
                                    actionLabel = "Annuler",
                                    onAction = { repo.save(row.toArticle(), row.text) }
                                )
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
