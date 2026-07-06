package com.example.myapp.undercover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.MyButton

@Composable
fun GameOverScreen(
    civiliansWon: Boolean,
    lastEliminated: Player,
    players: List<Player>,
    gameWord: String,
    impostorWord: String?,
    mrWhiteGuesses: Map<String, String>,
    onContinue: () -> Unit
) {
    val activePlayers = players.activePlayers()
    val mrWhiteWon = !civiliansWon && lastEliminated.role == PlayerRole.MR_WHITE

    val winnerText = when {
        civiliansWon -> "Les civils ont gagné !"
        mrWhiteWon -> "M. White (${lastEliminated.name}) a gagné !"
        activePlayers.any { it.role == PlayerRole.MR_WHITE } -> "M. White a gagné !"
        else -> if (activePlayers.count { it.role == PlayerRole.IMPOSTOR } > 1) "Les Undercover ont gagné !" else "L'Undercover a gagné !"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Green when the civilians take it, red when the impostors or Mr White do
        val winnerColor = if (civiliansWon) Color(0xFF4CAF50) else Color(0xFFF44336)

        Text(
            winnerText,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = winnerColor
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!mrWhiteWon && mrWhiteGuesses.isEmpty()) {
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
            "Mot civil : $gameWord",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        if (!impostorWord.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Mot undercover : $impostorWord",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }

        if (mrWhiteGuesses.isNotEmpty() && !mrWhiteWon) {
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
