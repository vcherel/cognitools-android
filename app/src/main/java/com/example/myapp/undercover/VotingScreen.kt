package com.example.myapp.undercover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapp.ShowAlertDialog

@Composable
fun VotingScreen(
    players: List<Player>,
    onPlayerEliminated: (Player) -> Unit
) {
    var selectedPlayer by remember { mutableStateOf<Player?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }

    val activePlayers = players.activePlayers()

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

        Spacer(modifier = Modifier.weight(0.3f))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 8.dp)
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

    if (showConfirmation) ShowAlertDialog(
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
