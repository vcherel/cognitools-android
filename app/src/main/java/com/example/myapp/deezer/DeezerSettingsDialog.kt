package com.example.myapp.deezer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Paste the ARL. Saving validates it by bootstrapping a session. Quality is fixed (MP3 128, everywhere). */
@Composable
fun DeezerSettingsDialog(
    repo: DeezerRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var arl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { arl = repo.settings.arl.first() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Réglages Deezer") },
        text = {
            Column {
                OutlinedTextField(
                    value = arl,
                    onValueChange = { arl = it },
                    label = { Text("ARL") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Validation…"
                        repo.settings.setArl(arl)
                        status = try {
                            val s = repo.ensureSession(forceRefresh = true)
                            busy = false
                            onSaved()
                            "OK (user ${s.userId})"
                        } catch (e: Exception) {
                            busy = false
                            "Échec: ${e.message}"
                        }
                    }
                }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
