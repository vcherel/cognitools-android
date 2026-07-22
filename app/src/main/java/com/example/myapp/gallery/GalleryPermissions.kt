package com.example.myapp.gallery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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

// True when the app has full shared-storage access (All files access), which lets it move,
// delete and rename any media directly, with no per-operation Android consent dialog. Below
// API 30 legacy storage already grants this, so it is always true there.
fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

// Sends the user to the system "All files access" settings page for this app, then re-checks on
// return. All files access can only be granted from settings, never through a runtime prompt.
@Composable
fun rememberAllFilesAccessRequester(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onResult() }
    return remember(launcher, context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                launcher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }
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
