package com.example.myapp.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch

/** Manage followed podcasts (with a remove button) and search the directory to add more. */
@Composable
fun PodcastSearchScreen(repo: PodcastRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val favorites by repo.favorites.collectAsState(initial = emptyList())
    val favoriteIds = favorites.map { it.id }.toSet()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PodcastSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        searching = true
        error = null
        scope.launch {
            runCatching { repo.search(q) }
                .onSuccess { results = it }
                .onFailure { error = it.message }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Podcasts", onBack = onBack)

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("Rechercher un podcast…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() })
            )

            error?.let {
                ErrorText(message = "Erreur: $it", onDismiss = { error = null }, modifier = Modifier.padding(top = 8.dp))
            }

            if (results == null) {
                if (favorites.isNotEmpty()) {
                    Text(
                        "Mes podcasts",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(favorites, key = { it.id }) { fav ->
                            FavoritePodcastRow(
                                favorite = fav,
                                onRemove = {
                                    scope.launch {
                                        repo.removeFavorite(fav.id)
                                        AppSnackbar.show(
                                            message = "« ${fav.title} » retiré",
                                            actionLabel = "Annuler",
                                            onAction = { repo.addFavorite(PodcastSearchResult(fav.id, fav.title, fav.author, fav.artworkUrl)) }
                                        )
                                    }
                                }
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Cherche un podcast pour l'ajouter à ta liste",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (searching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(results.orEmpty(), key = { it.feedUrl }) { result ->
                        SearchResultRow(
                            result = result,
                            isFavorite = result.feedUrl in favoriteIds,
                            onToggleFavorite = {
                                scope.launch {
                                    if (result.feedUrl in favoriteIds) repo.removeFavorite(result.feedUrl)
                                    else repo.addFavorite(result)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritePodcastRow(favorite: PodcastFavorite, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PodcastArt(favorite.artworkUrl, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(favorite.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (favorite.author.isNotBlank()) {
                Text(
                    favorite.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Retirer des podcasts suivis")
        }
    }
}

@Composable
private fun SearchResultRow(result: PodcastSearchResult, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PodcastArt(result.artworkUrl, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (result.author.isNotBlank()) {
                Text(
                    result.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = if (isFavorite) "Déjà suivi" else "Suivre ce podcast",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
