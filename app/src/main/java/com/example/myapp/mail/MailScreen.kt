package com.example.myapp.mail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.copyToClipboard
import com.example.myapp.mailRepository
import com.example.myapp.news.newsRelativeTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Yahoo inbox. Opening the screen fetches it, the header button fetches again; nothing is fetched
 * otherwise. A mail carrying a one-time code shows it as a chip that copies it in one tap.
 */
@Composable
fun MailScreen(onBack: () -> Unit, onOpenMessage: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = context.mailRepository
    val state by repo.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    // Once per visit, not again when coming back from a mail.
    var fetched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!fetched) {
            fetched = true
            repo.refresh()
        }
    }
    LaunchedEffect(state.needsLogin) { if (state.needsLogin) showSettings = true }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Mail",
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleLarge,
            titleWeight = true
        ) {
            IconButton(onClick = repo::refresh, enabled = !state.loading) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Actualiser")
                }
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Compte Yahoo")
            }
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }

        state.error?.let {
            ErrorText(message = it, onDismiss = {}, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        when {
            state.needsLogin -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                MyButton(text = "Connecter Yahoo", height = 64.dp, onClick = { showSettings = true })
            }
            state.messages.isEmpty() && !state.loading && state.error == null ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Boîte de réception vide.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.messages, key = { it.uid }) { message ->
                    MailRow(
                        message = message,
                        onClick = { onOpenMessage(message.uid) },
                        onDelete = { repo.delete(message.uid) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (showSettings) {
        MailSettingsDialog(
            settings = repo.settings,
            onDismiss = { showSettings = false },
            onSaved = {
                showSettings = false
                repo.refresh()
            }
        )
    }
}

@Composable
private fun MailRow(message: MailMessage, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.from,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    " · ${newsRelativeTime(message.date)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                message.subject.ifBlank { "(Sans objet)" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (message.preview.isNotBlank()) {
                Text(
                    message.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        message.code?.let { code ->
            Spacer(Modifier.width(10.dp))
            CodeChip(code)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The detected code; a tap puts it on the clipboard. */
@Composable
fun CodeChip(code: String) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable {
            copyToClipboard(context, code, "Code")
            AppSnackbar.show("Code $code copié")
        }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(code, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copier le code", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MailSettingsDialog(settings: MailSettings, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        address = settings.address.first()
        password = settings.password.first()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compte Yahoo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Adresse") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe d'application") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Pas ton mot de passe Yahoo : un mot de passe d'application, à générer dans Yahoo, Sécurité du compte, Mots de passe d'application.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank() && password.isNotBlank(),
                onClick = {
                    scope.launch {
                        settings.save(address, password)
                        onSaved()
                    }
                }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
