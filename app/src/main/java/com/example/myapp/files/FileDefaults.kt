package com.example.myapp.files

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

val Context.fileDefaultsDataStore by preferencesDataStore("file_defaults")

const val EXTRA_OPENED_EXTENSION = "com.example.myapp.files.EXTENSION"

/**
 * Which app opens which kind of file. The first file of an extension goes through the system
 * "Ouvrir avec" sheet, whose choice is captured by [FileOpenChoiceReceiver] and kept here; every
 * later file of that extension is handed straight to that app.
 */
object FileDefaults {
    private val KEY = stringPreferencesKey("apps")

    /** Extension (lowercase, no dot) to the flattened component name of the app that opens it. */
    fun flow(context: Context): Flow<Map<String, String>> =
        context.fileDefaultsDataStore.data.map { decode(it[KEY]) }

    suspend fun remember(context: Context, extension: String, component: String) {
        if (extension.isBlank()) return
        val updated = current(context) + (extension to component)
        context.fileDefaultsDataStore.edit { it[KEY] = encode(updated) }
    }

    suspend fun forget(context: Context, extension: String) {
        val updated = current(context) - extension
        context.fileDefaultsDataStore.edit { it[KEY] = encode(updated) }
    }

    private suspend fun current(context: Context): Map<String, String> =
        decode(context.fileDefaultsDataStore.data.first()[KEY])

    private fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.optString(it) }.filterValues { it.isNotBlank() }
    }

    private fun encode(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (extension, component) -> obj.put(extension, component) }
        return obj.toString()
    }
}

/** The app's own name for a stored component, falling back to its package when it is gone. */
fun appLabelFor(context: Context, component: String): String {
    val name = ComponentName.unflattenFromString(component) ?: return component
    return try {
        context.packageManager.getActivityInfo(name, 0).loadLabel(context.packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        name.packageName
    }
}

/** Fired by the system chooser once an app is picked, telling us what to reuse next time. */
class FileOpenChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extension = intent.getStringExtra(EXTRA_OPENED_EXTENSION) ?: return
        @Suppress("DEPRECATION")
        val chosen = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FileDefaults.remember(app, extension, chosen.flattenToString())
            } finally {
                pending.finish()
            }
        }
    }
}
