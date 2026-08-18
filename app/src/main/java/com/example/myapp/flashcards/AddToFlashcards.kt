package com.example.myapp.flashcards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.MyButton
import com.example.myapp.flashcardRepository
import kotlinx.coroutines.launch

/** Both sides go in with a capital, the way the lists are written. */
fun capitalizedCard(text: String): String =
    text.trim().replaceFirstChar { it.uppercaseChar() }

/**
 * Files a word into a flashcard list: the list is picked first when there is no obvious one, then
 * the card is shown for a last edit before it is written, since what a lookup or a grid hands over
 * rarely reads the way a card should. Shared by the translator and the mots fleches clue bar.
 */
@Composable
fun AddToFlashcardsDialog(
    word: String,
    definition: String,
    defaultListName: String? = null,
    wordLabel: String = "Mot",
    definitionLabel: String = "Définition",
    startWithPicker: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { context.flashcardRepository }
    val scope = rememberCoroutineScope()
    val lists by repo.observeLists().collectAsState(initial = emptyList())

    var chosen by remember { mutableStateOf<FlashcardList?>(null) }
    var picked by remember { mutableStateOf(false) }
    val target = chosen ?: defaultListName
        ?.takeUnless { startWithPicker || picked }
        ?.let { name -> lists.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    if (target == null) {
        AppDialog(onDismiss = onDismiss) {
            Text("Ajouter à quelle liste ?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                lists.forEach { list ->
                    Text(
                        text = list.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = list }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }
        return
    }

    var name by remember(target) { mutableStateOf(capitalizedCard(word)) }
    var back by remember(target) { mutableStateOf(capitalizedCard(definition)) }

    AppDialog(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "Ajouter à ${target.name}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (lists.size > 1) {
                TextButton(onClick = {
                    chosen = null
                    picked = true
                }) {
                    Text("Changer", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(wordLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = back,
            onValueChange = { back = it },
            label = { Text(definitionLabel) },
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
                onClick = onDismiss
            )
            MyButton(
                text = "Ajouter",
                modifier = Modifier.weight(1f),
                height = 48.dp,
                fontSize = 16.sp,
                enabled = name.isNotBlank() && back.isNotBlank(),
                onClick = {
                    val element = FlashcardElement(
                        listId = target.id,
                        name = name.trim(),
                        definition = back.trim()
                    )
                    scope.launch {
                        repo.addElement(target.id, element)
                        AppSnackbar.show("Ajouté à ${target.name}")
                    }
                    onDismiss()
                }
            )
        }
    }
}
