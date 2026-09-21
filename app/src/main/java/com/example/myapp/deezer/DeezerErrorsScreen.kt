package com.example.myapp.deezer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppSnackbar
import com.example.myapp.ScreenTopBar
import com.example.myapp.copyToClipboard
import com.example.myapp.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Deezer error log, newest first, with the whole thing one tap away on the clipboard: the
 * exact API wording is what a fix starts from, and the phone is rarely plugged into adb when
 * the error happens.
 */
@Composable
fun DeezerErrorsScreen(repo: DeezerRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.IO) { repo.errorLog.readLines().asReversed() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Journal d'erreurs", onBack = onBack) {
            Spacer(Modifier.weight(1f))
            IconButton(
                enabled = lines.isNotEmpty(),
                onClick = {
                    copyToClipboard(context, lines.joinToString("\n"), "Erreurs Deezer")
                    AppSnackbar.show("${lines.size} erreur${plural(lines.size)} copiée${plural(lines.size)}")
                }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Tout copier")
            }
            IconButton(
                enabled = lines.isNotEmpty(),
                onClick = {
                    repo.errorLog.clear()
                    lines = emptyList()
                }
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Vider")
            }
        }

        if (lines.isEmpty()) {
            Text(
                "Aucune erreur enregistrée",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
            }
        }
    }
}
