package com.example.myapp.mail

import android.content.Intent
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapp.ScreenTopBar
import com.example.myapp.mailRepository
import com.example.myapp.news.newsFullDate

/**
 * One mail, drawn as it was sent in a WebView (no JavaScript). A link opens outside the app, in the
 * browser or whichever app handles it.
 */
@Composable
fun MailMessageScreen(uid: Long, onBack: () -> Unit) {
    val repo = LocalContext.current.mailRepository
    val state by repo.state.collectAsState()
    val message = state.messages.firstOrNull { it.uid == uid }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = message?.from.orEmpty(),
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleLarge,
            titleMaxLines = 1,
            titleWeight = true
        ) {
            if (message != null) {
                IconButton(onClick = {
                    repo.delete(uid)
                    onBack()
                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Supprimer")
                }
            }
        }
        if (message == null) return@Column

        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Column {
                    Text(message.subject.ifBlank { "(Sans objet)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOf(message.fromAddress, newsFullDate(message.date)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            message.code?.let {
                Spacer(Modifier.width(10.dp))
                CodeChip(it)
            }
        }

        val html = message.html
        if (html == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator() else Text("Contenu non chargé.")
            }
        } else {
            MailWebView(html, Modifier.fillMaxSize().padding(top = 8.dp))
        }
    }
}

@Composable
private fun MailWebView(html: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    )
}
