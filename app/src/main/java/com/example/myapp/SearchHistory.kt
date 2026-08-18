package com.example.myapp

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.searchHistoryDataStore by preferencesDataStore("search_history")

// Newline separated, most recent first. A query can't contain a newline (every search field here is
// singleLine), so no escaping is needed.
private const val SEPARATOR = "\n"
private const val MAX_ENTRIES = 4

/**
 * The searches each search field remembers, one independent list per [SearchSurface]. Kept in its
 * own DataStore file so clearing it never touches anything else.
 */
enum class SearchSurface(internal val key: String) {
    MUSIC("music"),
    PODCAST("podcast"),
    NOTES("notes"),
    CITY("city")
}

object SearchHistory {

    // Its own scope, not the caller's: the moment a search is worth remembering is often the moment
    // the screen goes away (opening a result), which would cancel a write launched from a
    // composition scope before DataStore got to it.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [remember], fire and forget, safe to call from a screen that is about to be left. */
    fun record(context: Context, surface: SearchSurface, query: String) {
        writeScope.launch { remember(context, surface, query) }
    }

    fun recent(context: Context, surface: SearchSurface): Flow<List<String>> =
        context.searchHistoryDataStore.data.map { prefs ->
            prefs[stringPreferencesKey(surface.key)]
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty()
                .take(MAX_ENTRIES)
        }

    /**
     * Records [query] as the most recent search on [surface]. Also drops any entry the new one was
     * typed on top of (a stored prefix of it, or it a prefix of a stored one), so typing "orelsan"
     * one letter at a time leaves a single chip rather than a trail of half-words.
     */
    suspend fun remember(context: Context, surface: SearchSurface, query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        context.searchHistoryDataStore.edit { prefs ->
            val prefKey = stringPreferencesKey(surface.key)
            val existing = prefs[prefKey]?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
            val kept = existing.filterNot { it.isPrefixRelatedTo(trimmed) }
            prefs[prefKey] = (listOf(trimmed) + kept).take(MAX_ENTRIES).joinToString(SEPARATOR)
        }
    }

    suspend fun forget(context: Context, surface: SearchSurface, query: String) {
        context.searchHistoryDataStore.edit { prefs ->
            val prefKey = stringPreferencesKey(surface.key)
            val existing = prefs[prefKey]?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
            prefs[prefKey] = existing.filterNot { it.equals(query, ignoreCase = true) }
                .joinToString(SEPARATOR)
        }
    }

    private fun String.isPrefixRelatedTo(other: String): Boolean =
        startsWith(other, ignoreCase = true) || other.startsWith(this, ignoreCase = true)
}

/**
 * The recent searches of [surface] as tappable chips, each with its own cross to forget it. Renders
 * nothing when there is no history, so callers can drop it in unconditionally; they are expected to
 * only show it while the field is empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentSearchChips(
    surface: SearchSurface,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recent by remember(surface) { SearchHistory.recent(context, surface) }
        .collectAsState(initial = emptyList())

    if (recent.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        recent.forEach { query ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(query) }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    query,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Oublier « $query »",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { scope.launch { SearchHistory.forget(context, surface, query) } }
                        .padding(5.dp)
                )
            }
        }
    }
}
