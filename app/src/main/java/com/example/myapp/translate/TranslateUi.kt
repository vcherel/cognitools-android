package com.example.myapp.translate

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.MyButton
import com.example.myapp.copyToClipboard
import com.example.myapp.flashcardRepository
import com.example.myapp.flashcards.AddToFlashcardsDialog
import com.example.myapp.userMessage
import java.util.Locale

/**
 * Reads text out loud in the language it is written in. The engine takes a moment to start, so a tap
 * before it is ready does nothing rather than queueing something that would play much later.
 */
@Composable
fun rememberSpeaker(): (String, TranslateLang) -> Unit {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) engine = created
        }
        val instance = created
        onDispose {
            instance.stop()
            instance.shutdown()
            engine = null
        }
    }

    return { text, lang ->
        engine?.let {
            it.language = Locale.forLanguageTag(lang.code)
            it.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translate")
        }
    }
}

/** The translation itself, its dictionary entries, and the copy / listen actions. */
@Composable
fun TranslationCard(
    result: TranslationResult,
    speak: (String, TranslateLang) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = result.translation,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { speak(result.translation, result.to) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Écouter")
            }
            IconButton(onClick = {
                copyToClipboard(context, result.translation)
                AppSnackbar.show("Copié")
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copier")
            }
        }

        if (result.entries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            result.detailWord?.let { DetailCaption("« $it »") }
            result.entries.forEach { entry ->
                PartOfSpeechRow(entry.partOfSpeech, entry.terms.joinToString(", ") { it.word })
            }
        }

        if (result.hasExtraDetails()) {
            var expanded by remember(result) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (expanded) "Moins de détails" else "Plus de détails",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) TranslationDetails(result)
        }
    }
}

/** True when the expander has more to show than the one line summary of each part of speech. */
private fun TranslationResult.hasExtraDetails(): Boolean =
    entries.any { entry -> entry.terms.any { it.back.isNotEmpty() } } ||
        definitions.isNotEmpty() || examples.isNotEmpty() || alternatives.isNotEmpty()

@Composable
private fun TranslationDetails(result: TranslationResult) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        if (result.alternatives.isNotEmpty()) {
            DetailCaption("Autres traductions")
            Text(result.alternatives.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
        }

        result.entries.filter { entry -> entry.terms.any { it.back.isNotEmpty() } }.forEach { entry ->
            DetailCaption(entry.partOfSpeech)
            entry.terms.forEach { term ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = term.word,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(110.dp)
                    )
                    Text(
                        text = term.back.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (result.definitions.isNotEmpty()) {
            DetailCaption("Définitions")
            result.definitions.groupBy { it.partOfSpeech }.forEach { (partOfSpeech, definitions) ->
                Text(
                    text = partOfSpeech,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                definitions.forEach { definition ->
                    Text(
                        text = "• ${definition.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    definition.example?.let {
                        Text(
                            text = "« $it »",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = muted,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (result.examples.isNotEmpty()) {
            DetailCaption("Exemples")
            result.examples.forEach {
                Text(
                    text = "« $it »",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = muted,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun PartOfSpeechRow(partOfSpeech: String, terms: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = partOfSpeech,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(88.dp)
        )
        Text(text = terms, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The list the big button always files into: what the translator is used for nearly every time. */
private const val DEFAULT_LIST_NAME = "Anglais"

/**
 * Sends the lookup to a flashcard list. The big button always files into [DEFAULT_LIST_NAME], so the
 * list is only asked for when it is not that one; either way the card is shown for a last edit before
 * it is written, since the translation often needs a word dropped or a gender fixed.
 */
@Composable
fun AddToFlashcardsButton(source: String, translation: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { context.flashcardRepository }
    val lists by remember { repo.observeLists() }.collectAsState(initial = emptyList())
    var filing by remember { mutableStateOf(false) }
    var startWithPicker by remember { mutableStateOf(false) }

    val target = lists.firstOrNull { it.name.equals(DEFAULT_LIST_NAME, ignoreCase = true) }

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MyButton(
            text = if (target != null) "Ajouter à ${target.name}" else "Ajouter aux flashcards",
            modifier = Modifier.weight(1f),
            height = 56.dp,
            fontSize = 16.sp,
            enabled = lists.isNotEmpty(),
            onClick = {
                startWithPicker = false
                filing = true
            }
        )
        MyButton(
            modifier = Modifier.width(64.dp),
            height = 56.dp,
            icon = Icons.AutoMirrored.Filled.List,
            text = "Choisir la liste",
            enabled = lists.isNotEmpty(),
            onClick = {
                startWithPicker = true
                filing = true
            }
        )
    }

    if (filing) {
        AddToFlashcardsDialog(
            word = source,
            definition = translation,
            defaultListName = DEFAULT_LIST_NAME,
            definitionLabel = "Traduction",
            startWithPicker = startWithPicker,
            onDismiss = { filing = false }
        )
    }
}

/**
 * The translator reduced to one word, opened from the reader by long pressing it. Same lookup and
 * same flashcard button as the full screen, without leaving the page being read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordLookupSheet(word: String, target: TranslateLang, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val speak = rememberSpeaker()
    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(word, target) {
        result = null
        error = null
        runCatching { translate(word, target) }
            .onSuccess {
                result = it
                TranslateStore.remember(context, LookupEntry(word, it.translation, it.to))
            }
            .onFailure { error = userMessage(it, "Traduction impossible") }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                val spoken = result?.from ?: target.other
                IconButton(onClick = { speak(word, spoken) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Écouter le mot")
                }
            }
            Spacer(Modifier.height(12.dp))

            val current = result
            when {
                error != null -> ErrorText(message = error!!, onDismiss = onDismiss)
                current == null -> CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
                else -> {
                    TranslationCard(result = current, speak = speak)
                    Spacer(Modifier.height(16.dp))
                    AddToFlashcardsButton(source = word, translation = current.translation)
                }
            }
        }
    }
}
