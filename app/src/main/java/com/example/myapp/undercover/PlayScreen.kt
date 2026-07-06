package com.example.myapp.undercover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.LocalIsDarkMode
import com.example.myapp.MyButton

@Composable
fun PlayScreen(
    round: Int,
    players: List<Player>,
    onContinue: () -> Unit
) {
    val activePlayers = players.filter { !it.isEliminated }
    val isDarkMode = LocalIsDarkMode.current

    var displayPlayers by remember(activePlayers) { mutableStateOf(activePlayers) }
    val startingPlayer = displayPlayers.firstOrNull()

    var selectedPlayer by remember { mutableStateOf<Player?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showPlayerInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize(),
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
        val colors = if (isDarkMode) {
            listOf(Color(0xFF0D47A1), Color(0xFF1976D2))
        } else {
            listOf(Color(0xFFE3F2FD), Color(0xFFBBDEFB))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(colors),
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

                Spacer(modifier = Modifier.height(10.dp))

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

                Spacer(modifier = Modifier.height(10.dp))

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
                Spacer(modifier = Modifier.height(10.dp))

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

        Spacer(modifier = Modifier.height(10.dp))

        if (startingPlayer != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { displayPlayers = activePlayers.shuffled() },
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Appuie pour changer",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(displayPlayers) { player ->
                PlayerCard(
                    player = player,
                    onClick = {
                        selectedPlayer = player
                        showConfirmDialog = true
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        MyButton(
            text = "Passons au conseil",
            onClick = onContinue,
            modifier = Modifier
                .height(90.dp)
                .widthIn(min = 180.dp, max = 250.dp),
            fontSize = 22.sp
        )
    }

    // Confirmation dialog
    if (showConfirmDialog && selectedPlayer != null) {
        AlertDialog(
            onDismissRequest = {
                showConfirmDialog = false
                selectedPlayer = null
            },
            title = {
                Text(
                    text = "Voir le rôle et le mot ?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        showPlayerInfo = true
                    }
                ) {
                    Text("Oui", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        selectedPlayer = null
                    }
                ) {
                    Text("Non", fontSize = 16.sp)
                }
            }
        )
    }

    // Player info dialog
    if (showPlayerInfo && selectedPlayer != null) {
        AlertDialog(
            onDismissRequest = {
                showPlayerInfo = false
                selectedPlayer = null
            },
            title = {
                Text(
                    text = selectedPlayer?.name ?: "",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val word = selectedPlayer?.word
                if (!word.isNullOrEmpty()) {
                    Text(
                        text = "Mot: $word",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "Pas de mot",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPlayerInfo = false
                        selectedPlayer = null
                    }
                ) {
                    Text("OK", fontSize = 16.sp)
                }
            }
        )
    }
}

@Composable
fun PlayerCard(
    player: Player,
    onClick: () -> Unit
) {
    val isDarkMode = LocalIsDarkMode.current
    val backgroundColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)

    Card(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = player.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
