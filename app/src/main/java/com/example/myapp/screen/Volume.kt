package com.example.myapp.screen

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var isBoostEnabled by remember { mutableStateOf(false) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }

    // Initialize LoudnessEnhancer
    LaunchedEffect(Unit) {
        try {
            // Get the audio session ID (0 means global audio session)
            val sessionId = 0
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                enabled = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            loudnessEnhancer?.release()
        }
    }

    BackHandler {
        // Disable boost when leaving
        if (isBoostEnabled) {
            loudnessEnhancer?.enabled = false
            isBoostEnabled = false
        }
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        IconButton(onClick = {
            // Disable boost when leaving
            if (isBoostEnabled) {
                loudnessEnhancer?.enabled = false
                isBoostEnabled = false
            }
            onBack()
        }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Volume Booster",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isBoostEnabled) "🔊 Activé (200%)" else "🔇 Désactivé",
                style = MaterialTheme.typography.headlineSmall,
                color = if (isBoostEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    loudnessEnhancer?.let { enhancer ->
                        if (isBoostEnabled) {
                            // Disable boost
                            enhancer.enabled = false
                            isBoostEnabled = false
                        } else {
                            // Enable boost at 200%
                            // LoudnessEnhancer uses millibels (mB)
                            // 1000 mB = +10 dB ≈ 2x volume (200%)
                            enhancer.setTargetGain(1000)
                            enhancer.enabled = true
                            isBoostEnabled = true
                        }
                    }
                },
                modifier = Modifier
                    .size(120.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBoostEnabled)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isBoostEnabled) "OFF" else "ON",
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ Attention",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "L'amplification du volume peut:\n" +
                                "• Endommager vos haut-parleurs\n" +
                                "• Causer une perte auditive\n" +
                                "• Créer de la distorsion audio\n\n" +
                                "Utilisez avec précaution!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (loudnessEnhancer == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "❌ Erreur",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Impossible d'initialiser l'amplificateur audio. Vérifiez les permissions.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}