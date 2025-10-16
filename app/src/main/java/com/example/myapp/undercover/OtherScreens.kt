package com.example.myapp.undercover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.MyButton
import com.example.myapp.ShowAlertDialog

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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Round $round",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Rules in a fancy rectangle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(listOf(Color(0xFFE3F2FD), Color(0xFFBBDEFB))),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Règles:",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    buildAnnotatedString {
                        append("• Chaque joueur dit un ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("mot lié à son mot secret")
                        }
                    },
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    buildAnnotatedString {
                        append("• Ne ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("JAMAIS")
                        }
                        append(" dire son mot OU mot de la même famille")
                    },
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    buildAnnotatedString {
                        append("• Dites pas des trucs trop simples, on est là\npour le ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("beau jeu")
                        }
                    },
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (startingPlayer != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Premier joueur :",
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = startingPlayer.name,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1565C0),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        MyButton(
            text = "Passons au conseil",
            onClick = onContinue,
            modifier = Modifier
                .height(90.dp)
                .widthIn(min = 180.dp, max = 250.dp),
            fontSize = 22.sp
        )
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Phase de vote",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.weight(0.7f))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
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
        Spacer(modifier = Modifier.weight(1.3f))
    }

    ShowAlertDialog(
        show = showConfirmation,
        onDismiss = { showConfirmation = false },
        title = "Confirmer assassinat",
        textContent = { Text("Vous êtes sûr de vouloir choisir ${selectedPlayer?.name} ?") },
        confirmText = "Oui",
        cancelText = "Non",
        onConfirm = {
            selectedPlayer?.let { onPlayerEliminated(it) }
            showConfirmation = false
        },
        onCancel = {
            showConfirmation = false
        }
    )
}

@Composable
fun EliminationResultScreen(
    player: Player,
    onNextRound: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${player.name} est éliminé !",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Il était :",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = player.role.displayName(),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = player.role.displayColor(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        MyButton(
            text = "Manche suivante",
            onClick = onNextRound,
            modifier = Modifier
                .height(90.dp)
                .widthIn(min = 180.dp, max = 250.dp)
        )
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
        activeRoles.contains(PlayerRole.MR_WHITE) -> "M. White a gagné !"
        else -> if (activeRoles.count { it == PlayerRole.IMPOSTOR } > 1) "Les Undercover ont gagné !" else "L'Undercover a gagné !"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val headerText = if (lastEliminated.role == PlayerRole.MR_WHITE && !civiliansWon) {
            "Bien joué c'était ça !"
        } else {
            "Perdu ! (Dommage)"
        }

        Text(
            headerText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            winnerText,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!(lastEliminated.role == PlayerRole.MR_WHITE && !civiliansWon) && mrWhiteGuesses.isEmpty()) {
            Text(
                "${lastEliminated.name} a été éliminé",
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Rôle: ${lastEliminated.role.displayName()}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = lastEliminated.role.displayColor(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "Le mot était $gameWord",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        if (mrWhiteGuesses.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            val message = when (mrWhiteGuesses.size) {
                1 -> {
                    val (name, guess) = mrWhiteGuesses.entries.first()
                    "$name a tenté \"$guess\" mais il s'est trompé"
                }
                else -> {
                    val guessesString = mrWhiteGuesses.entries.joinToString(separator = "\n") { (name, guess) ->
                        "- $name a tenté \"$guess\""
                    }
                    "$guessesString\nMais ils se sont tous trompés"
                }
            }

            Text(
                message,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        MyButton(
            text = "Voir classement",
            onClick = onContinue,
            modifier = Modifier.widthIn(min = 180.dp, max = 250.dp).height(90.dp),
            fontSize = 24.sp
        )
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
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sortedScores = allScores.entries.sortedByDescending { it.value }
            items(sortedScores) { entry ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(
                            color = Color(0xFFECEFF1),
                            shape = RoundedCornerShape(20)
                        )
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.key,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${entry.value} pts",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        MyButton(
            text = "Menu principal",
            onClick = onBackToMenu,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MyButton(
                text = "Rejouer",
                onClick = onNewGame,
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
            )

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = Color(0xFFECEFF1),
                        shape = RoundedCornerShape(25)
                    )
                    .clickable { onSettings() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Paramètres",
                    modifier = Modifier.size(32.dp)
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${lastEliminated.name} (${lastEliminated.role.displayName()}) was just eliminated!",
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
                    "Si tu devines tu gagnes ! (sinon tu dégages..)",
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
