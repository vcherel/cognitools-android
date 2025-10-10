package com.example.myapp.screen

import android.media.audiofx.LoudnessEnhancer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun VolumeBoosterScreen(onBack: () -> Unit) {
    var isBoostEnabled by remember { mutableStateOf(false) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }

    // Initialize LoudnessEnhancer
    LaunchedEffect(Unit) {
        try {
            // Audio session ID 0 = global audio session (all audio output)
            loudnessEnhancer = LoudnessEnhancer(0).apply {
                enabled = false
            }
        } catch (_: Exception) {
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

    // Top bar
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text(
                "Volume Booster",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isBoostEnabled) "Activé" else "Désactivé",
            style = MaterialTheme.typography.headlineMedium,
            color = if (isBoostEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(120.dp, 60.dp)
                .background(Color(0xFFECEFF1), RoundedCornerShape(50))
                .shadow(50.dp, RoundedCornerShape(50))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Switch(
                checked = isBoostEnabled,
                onCheckedChange = { enabled ->
                    loudnessEnhancer?.let { enhancer ->
                        enhancer.enabled = enabled
                        if (enabled) enhancer.setTargetGain(5000)
                        isBoostEnabled = enabled
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .scale(2.5f)
            )
        }
    }
}