package com.example.myapp.gallery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun readMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

fun hasReadMediaPermission(context: Context): Boolean =
    readMediaPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

// Bridges the launcher-based consent flow (createWriteRequest/createDeleteRequest PendingIntents,
// and RecoverableSecurityException recovery) into a plain suspend call the repository functions
// can await.
@Composable
fun rememberIntentSenderRequester(): suspend (IntentSender) -> Boolean {
    var continuation by remember { mutableStateOf<CancellableContinuation<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        continuation?.resume(result.resultCode == Activity.RESULT_OK)
        continuation = null
    }
    return remember(launcher) {
        { sender: IntentSender ->
            suspendCancellableCoroutine { cont ->
                continuation = cont
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
    }
}
