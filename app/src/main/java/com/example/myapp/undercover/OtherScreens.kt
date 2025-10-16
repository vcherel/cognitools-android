package com.example.myapp.undercover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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

@Composable
fun PlayScreen(
    round: Int,
    players: List<Player>,
    currentPlayerIndex: Int,
    onContinue: () -> Unit
) {
    val activePlayers = players.filter { !it.isEliminated }
    val startingPlayer = activePlayers.getOrNull(currentPlayerIndex)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Round $round",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Règles:",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "• Chaque joueur dit un mot lié à son mot secret (bon courage M. White)",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "• Ne JAMAIS dire son mot OU mot de la même famille",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        Text(
            "• Dites pas des trucs trop simples on est là pour le beau jeu",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (startingPlayer != null) {
            Text(
                "Premier joueur: ${startingPlayer.name}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Et après on tourne",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onContinue) {
            Text("Passons au conseil")
        }
    }
}

@Composable
fun VotingScreen(
    players: List<Player>,
    onPlayerEliminated: (Player) -> Unit
) {
    var selectedPlayer by remember { mutableStateOf<Player?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }

    val activePlayers by remember { derivedStateOf { players.activePlayers() } }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Phase de vote",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Qui c'est qu'on zigouille ?",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(activePlayers) { player ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedPlayer = player
                            showConfirmation = true
                        },
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Text(
                        player.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (showConfirmation && selectedPlayer != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Confirmer assassinat") },
            text = { Text("Vous êtes sûr de vouloir choisir ${selectedPlayer?.name} ?") },
            confirmButton = {
                Button(onClick = {
                    selectedPlayer?.let { onPlayerEliminated(it) }
                    showConfirmation = false
                }) {
                    Text("Oui")
                }
            },
            dismissButton = {
                Button(onClick = { showConfirmation = false }) {
                    Text("Non")
                }
            }
        )
    }
}

@Composable
fun EliminationResultScreen(
    player: Player,
    onNextRound: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "${player.name} est éliminé !",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Il était :",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            player.role.displayName(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = player.role.displayColor()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onNextRound) {
            Text("Manche suivante")
        }
    }
}

@Composable
fun GameOverScreen(
    civiliansWon: Boolean,
    lastEliminated: Player,
    players: List<Player>,
    gameWord: String,
    mrWhiteGuesses: Map<String, String>,
    onContinue: () -> Unit
) {
    val activePlayers by remember { derivedStateOf { players.activePlayers() } }
    val activeRoles by remember { derivedStateOf { activePlayers.map { it.role }.toSet() } }

    val winnerText = when {
        civiliansWon -> "Les civils ont gagné !"
        lastEliminated.role == PlayerRole.MR_WHITE -> "M. White (${lastEliminated.name}) a gagné !"
        else -> when {
            activeRoles.contains(PlayerRole.IMPOSTOR) ->
                "Impostor and Mr White Win!"
            activeRoles.contains(PlayerRole.IMPOSTOR) -> "Les imposteurs ont gagné !"
            activeRoles.contains(PlayerRole.MR_WHITE) -> "Mr White Win!"
            else ->
                if (activeRoles.count { it == PlayerRole.IMPOSTOR } > 1) "Les Undercover ont gagné !"
                else "L'Undercover a gagné !"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val headerText = if (lastEliminated.role == PlayerRole.MR_WHITE && !civiliansWon) {
            "Bien joué c'était ça!"
        } else {
            "Perdu ! (Dommage)"
        }

        Text(
            headerText,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            winnerText,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!(lastEliminated.role == PlayerRole.MR_WHITE && !civiliansWon) && mrWhiteGuesses.isEmpty()) {
            Text(
                "${lastEliminated.name} a été éliminado",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Rôle: ${lastEliminated.role.displayName()}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = lastEliminated.role.displayColor()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "Le mot était $gameWord",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )

        if (mrWhiteGuesses.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            val message = when (mrWhiteGuesses.size) {
                1 -> {
                    val (name, guess) = mrWhiteGuesses.entries.first()
                    "$name a mis \"$guess\" mais il s'est trompé"
                }
                else -> {
                    val guessesString = mrWhiteGuesses.entries.joinToString(separator = "\n") { (name, guess) ->
                        "- $name a mis \"$guess\""
                    }
                    "$guessesString\n. Mais ils se sont tous trompé"
                }
            }

            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onContinue) {
            Text("Voir classement")
        }
    }
}

@Composable
fun LeaderboardScreen(
    allScores: Map<String, Int>,
    onBackToMenu: () -> Unit,
    onNewGame: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Classement",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sortedScores = allScores.entries.sortedByDescending { it.value }
            items(sortedScores) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(entry.key, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${entry.value} pts",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onBackToMenu) {
            Text("Menu principal")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Play Again + Settings row
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onNewGame) {
                Text("Rejouer")
            }

            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Paramètres",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

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
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${lastEliminated.name} (${lastEliminated.role.displayName()}) was just eliminated!",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = lastEliminated.role.displayColor(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (scenario) {

            // CASE 1 — Eliminated Mr White
            is MrWhiteScenario.EliminatedMrWhite -> {
                Text(
                    "M. White doit maintenant deviner le mot",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Si tu devines tu gagnes ! (sinon tu dégages..)",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }

            // CASE 2 — Final Two Players (Mr White + Impostor/Civilian)
            is MrWhiteScenario.FinalTwo -> {
                val mrWhite = scenario.mrWhite
                val opponent = scenario.opponent

                Text(
                    text = "Seulement deux joueurs restant !",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "${mrWhite.name} (M. White) vs ${opponent.name} (${opponent.role.displayName()})",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "M. White va tenter de deviner le mot",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "S'il trouve, ${mrWhite.name} gagne ! Sinon, ${opponent.name} gagne !",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }

            // CASE 3 — Only Mr Whites Remaining
            is MrWhiteScenario.OnlyMrWhitesLeft -> {
                val mrWhites = scenario.activeMrWhites
                val currentGuesser = scenario.currentGuesser

                Text(
                    text = "Il ne reste plus que des M. White !",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "${mrWhites.size} M. Whites restant",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "C'est au tour de ${currentGuesser.name} de tenter de deviner le mot",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "S'il le devine correctement, il gagne (seul) ! Sinon les autres M. White ont leur chance",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Common input UI (for all cases)
        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                    onDone = {
                        if (guessedWord.isNotBlank()) showConfirmation = true
                    }
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = { showConfirmation = true },
                enabled = guessedWord.isNotBlank()
            ) {
                Text("Valider")
            }
        }
    }

    // Confirmation Dialog
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Confirmer") },
            text = { Text("'$guessedWord' es ton dernier mot ?") },
            confirmButton = {
                Button(onClick = {
                    onGuessSubmitted(guessedWord.trim())
                    showConfirmation = false
                    guessedWord = ""
                }) {
                    Text("Oui")
                }
            },
            dismissButton = {
                Button(onClick = { showConfirmation = false }) {
                    Text("Non")
                }
            }
        )
    }
}