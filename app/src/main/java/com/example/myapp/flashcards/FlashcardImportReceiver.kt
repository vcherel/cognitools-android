package com.example.myapp.flashcards

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myapp.flashcardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Upserts a flashcards bundle pushed into the app's external files dir, named by the "file" extra.
 * Only the adb shell can send it (the manifest requires DUMP), and `am broadcast` prints the result.
 */
class FlashcardImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("file") ?: return
        val pending = goAsync()
        val app = context.applicationContext
        importScope.launch {
            try {
                val file = File(app.getExternalFilesDir(null), File(name).name)
                app.flashcardRepository.importFromJson(file.readText())
                file.delete()
                pending.setResult(Activity.RESULT_OK, "imported", null)
            } catch (e: Exception) {
                pending.setResult(Activity.RESULT_CANCELED, e.toString(), null)
            } finally {
                pending.finish()
            }
        }
    }
}
