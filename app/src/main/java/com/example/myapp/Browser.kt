package com.example.myapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hands a link to whichever app handles it, the browser for a web page. */
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        AppSnackbar.show("Aucune application pour ouvrir le lien")
    }
}
