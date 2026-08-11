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

/**
 * Sends the lookup to a flashcard list. The list used last is remembered, so filing a word is one
 * tap; the second button is there to send this one somewhere else.
 */
@Composable
fun AddToFlashcardsButton(source: String, translation: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { context.flashcardRepository }
    val scope = rememberCoroutineScope()
    val lists by repo.observeLists().collectAsState(initial = emptyList())
    val rememberedId by TranslateStore.flashcardListId(context).collectAsState(initial = null)
    var picking by remember { mutableStateOf(false) }

    val target = lists.firstOrNull { it.id == rememberedId }

    val add: (FlashcardList) -> Unit = { list ->
        scope.launch {
            repo.addElement(
                list.id,
                FlashcardElement(listId = list.id, name = source.trim(), definition = translation.trim())
            )
            TranslateStore.setFlashcardListId(context, list.id)
            AppSnackbar.show("Ajouté à ${list.name}")
        }
        picking = false
    }

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MyButton(
            text = if (target != null) "Ajouter à ${target.name}" else "Ajouter aux flashcards",
            modifier = Modifier.weight(1f),
            height = 56.dp,
            fontSize = 16.sp,
            enabled = lists.isNotEmpty(),
            onClick = { if (target != null) add(target) else picking = true }
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
                            .clickable { add(list) }
                            .padding(vertical = 12.dp)
                    )
                }
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
