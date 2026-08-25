package com.example.myapp

import android.content.Intent
import android.net.Uri

/**
 * The one place a "send these files somewhere" intent is built. One uri is an ACTION_SEND, several
 * an ACTION_SEND_MULTIPLE, and the read grant applies either way. The caller decides where it goes:
 * the gallery's share sheet targets one package directly, the file explorer opens a chooser.
 */
fun shareUrisIntent(uris: List<Uri>, mimeType: String): Intent {
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    return intent.setType(mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
