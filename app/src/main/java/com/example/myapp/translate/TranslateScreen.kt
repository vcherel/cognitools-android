package com.example.myapp.translate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapp.ErrorText
import com.example.myapp.ScreenTopBar
import com.example.myapp.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Nothing is sent while the words are still being typed. */
private const val DEBOUNCE_MS = 700L

/** Past this the lookup is a text, not a word to learn, and the flashcard button steps aside. */
private const val MAX_FLASHCARD_WORDS = 4

@Composable
fun TranslateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speak = rememberSpeaker()

    val target by TranslateStore.target(context).collectAsState(initial = TranslateLang.FR)
    val history by TranslateStore.history(context).collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(input, target) {
        val text = input.trim()
        if (text.isEmpty()) {
            result = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        loading = true
        error = null
        runCatching { translate(text, target) }
            .onSuccess {
                result = it
                TranslateStore.remember(context, LookupEntry(text, it.translation, it.to))
            }
            .onFailure {
                result = null
                error = userMessage(it, "Traduction impossible")
            }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Traducteur", onBack = onBack) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { TranslateStore.setTarget(context, target.other) } }) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Inverser le sens")
            }
        }

        // The field and its translation sit in the middle of the screen rather than under the
        // header: one screen tall whatever it holds, with the history following underneath.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageHeight = maxHeight

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = pageHeight),
                    verticalArrangement = Arrangement.Center
                ) {
                    // The source is whatever was detected, so the direction reads as detected to chosen.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = result?.from?.label ?: "Détection",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "  →  ",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = target.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        placeholder = { Text("Mot ou texte à traduire") },
                        trailingIcon = {
                            if (input.isNotEmpty()) {
                                IconButton(onClick = { input = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Effacer")
                                }
                            }
                        }
                    )

                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        ErrorText(message = it, onDismiss = { error = null })
                    }

                    result?.let { current ->
                        Spacer(Modifier.height(16.dp))
                        SelectionContainer {
                            TranslationCard(result = current, speak = speak)
                        }
                        if (current.source.split(" ").size <= MAX_FLASHCARD_WORDS) {
                            Spacer(Modifier.height(16.dp))
                            AddToFlashcardsButton(source = current.source, translation = current.translation)
                        }
                    }
                }

                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Récent",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { scope.launch { TranslateStore.clearHistory(context) } }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Vider l'historique")
                        }
                    }
                    HorizontalDivider()
                    history.forEach { entry ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { input = entry.source }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(entry.source, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = entry.translation,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
