package com.example.myapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Puts [text] on the system clipboard. Works from a service too, unlike the Compose clipboard local. */
fun copyToClipboard(context: Context, text: String, label: String = "CogniTools") {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}
