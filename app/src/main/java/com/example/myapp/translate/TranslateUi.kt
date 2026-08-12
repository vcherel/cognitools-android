package com.example.myapp.translate

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.MyButton
import com.example.myapp.flashcardRepository
import com.example.myapp.flashcards.FlashcardElement
import com.example.myapp.flashcards.FlashcardList
import kotlinx.coroutines.launch
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
    val clipboard = LocalClipboardManager.current

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
                clipboard.setText(AnnotatedString(result.translation))
                AppSnackbar.show("Copié")
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copier")
            }
        }

        if (result.entries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            result.entries.forEach { entry ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = entry.partOfSpeech,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(88.dp)
                    )
                    Text(
                        text = entry.terms.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** The list the big button always files into: what the translator is used for nearly every time. */
private const val DEFAULT_LIST_NAME = "Anglais"

/** Both sides go in with a capital, the way the lists are written. */
private fun capitalized(text: String): String =
    text.trim().replaceFirstChar { it.uppercaseChar() }

/**
 * Sends the lookup to a flashcard list. The big button always files into [DEFAULT_LIST_NAME], so the
 * list is only asked for when it is not that one; either way the card is shown for a last edit before
 * it is written, since the translation often needs a word dropped or a gender fixed.
 */
@Composable
fun AddToFlashcardsButton(source: String, translation: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { context.flashcardRepository }
    val scope = rememberCoroutineScope()
    val lists by repo.observeLists().collectAsState(initial = emptyList())
    var picking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<FlashcardList?>(null) }

    val target = lists.firstOrNull { it.name.equals(DEFAULT_LIST_NAME, ignoreCase = true) }

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MyButton(
            text = if (target != null) "Ajouter à ${target.name}" else "Ajouter aux flashcards",
            modifier = Modifier.weight(1f),
            height = 56.dp,
            fontSize = 16.sp,
            enabled = lists.isNotEmpty(),
            onClick = { if (target != null) confirming = target else picking = true }
        )
        MyButton(
            modifier = Modifier.width(64.dp),
            height = 56.dp,
            icon = Icons.AutoMirrored.Filled.List,
            text = "Choisir la liste",
            enabled = lists.isNotEmpty(),
            onClick = { picking = true }
        )
    }

    if (picking) {
        AppDialog(onDismiss = { picking = false }) {
            Text("Ajouter à quelle liste ?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                lists.forEach { list ->
                    Text(
                        text = list.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                picking = false
                                confirming = list
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }
    }

    confirming?.let { list ->
        var name by remember(list, source) { mutableStateOf(capitalized(source)) }
        var definition by remember(list, translation) { mutableStateOf(capitalized(translation)) }

        AppDialog(onDismiss = { confirming = null }) {
            Text("Ajouter à ${list.name}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Mot") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = definition,
                onValueChange = { definition = it },
                label = { Text("Traduction") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(
                    text = "Annuler",
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    fontSize = 16.sp,
                    onClick = { confirming = null }
                )
                MyButton(
                    text = "Ajouter",
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    fontSize = 16.sp,
                    enabled = name.isNotBlank() && definition.isNotBlank(),
                    onClick = {
                        val element = FlashcardElement(
                            listId = list.id,
                            name = name.trim(),
                            definition = definition.trim()
                        )
                        scope.launch {
                            repo.addElement(list.id, element)
                            AppSnackbar.show("Ajouté à ${list.name}")
                        }
                        confirming = null
                    }
                )
            }
        }
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
            .onFailure { error = it.message ?: "Traduction impossible" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
