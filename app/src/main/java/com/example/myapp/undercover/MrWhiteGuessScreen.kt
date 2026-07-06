package com.example.myapp.undercover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog

@Composable
fun MrWhiteGuessScreen(
    lastEliminated: Player,
    scenario: MrWhiteScenario,
    onGuessSubmitted: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var guessedWord by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

    // Auto focus input field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${lastEliminated.name} (${lastEliminated.role.displayName()}) est mort !",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = lastEliminated.role.displayColor(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (scenario) {
            is MrWhiteScenario.EliminatedMrWhite -> {
                Text(
                    "M. White doit maintenant deviner le mot",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Si tu devines tu gagnes !\n(sinon tu dégages..)",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }

            is MrWhiteScenario.FinalTwo -> {
                val mrWhite = scenario.mrWhite
                val opponent = scenario.opponent

                Text(
                    "Seulement deux joueurs restant !",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "${mrWhite.name} (M. White) vs ${opponent.name} (${opponent.role.displayName()})",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "M. White va tenter de deviner le mot",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "S'il trouve, ${mrWhite.name} gagne ! Sinon, ${opponent.name} gagne !",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }

            is MrWhiteScenario.OnlyMrWhitesLeft -> {
                val mrWhites = scenario.activeMrWhites
                val currentGuesser = scenario.currentGuesser

                Text(
                    "Plus que des M. White !",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "${mrWhites.size} M. Whites restant",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${currentGuesser.name} doit deviner le mot",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "S'il devine le mot, il gagne seul !",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Sinon les autres M. White ont leur chance",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Input and submit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = guessedWord,
                onValueChange = { guessedWord = it },
                label = { Text("Devine") },
                placeholder = { Text("Ton guess") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (guessedWord.isNotBlank()) showConfirmation = true }
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            MyButton(
                text = "Valider",
                onClick = { showConfirmation = true },
                enabled = guessedWord.isNotBlank(),
                modifier = Modifier.height(60.dp).width(120.dp),
                fontSize = 18.sp
            )
        }
    }

    // Confirmation dialog using ShowAlertDialog
    ShowAlertDialog(
        show = showConfirmation,
        onDismiss = { showConfirmation = false },
        title = "Confirmer",
        textContent = { Text("'$guessedWord' est ton dernier mot ?", fontSize = 16.sp) },
        confirmText = "Oui",
        cancelText = "Non",
        onConfirm = {
            onGuessSubmitted(guessedWord.trim())
            guessedWord = ""
            showConfirmation = false
        },
        onCancel = { showConfirmation = false }
    )
}
