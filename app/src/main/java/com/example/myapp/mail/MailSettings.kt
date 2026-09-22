package com.example.myapp.mail

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mailDataStore by preferencesDataStore("mail_prefs")

private val KEY_ADDRESS = stringPreferencesKey("address")
private val KEY_PASSWORD = stringPreferencesKey("app_password")

/** The Yahoo address and its app password. The password only ever goes to Yahoo's IMAP server. */
class MailSettings(private val context: Context) {

    val address: Flow<String> = context.mailDataStore.data.map { it[KEY_ADDRESS].orEmpty() }

    val password: Flow<String> = context.mailDataStore.data.map { it[KEY_PASSWORD].orEmpty() }

    suspend fun save(address: String, password: String) {
        context.mailDataStore.edit {
            it[KEY_ADDRESS] = address.trim()
            // Yahoo shows the app password in groups of four; the spaces are not part of it.
            it[KEY_PASSWORD] = password.filterNot { c -> c.isWhitespace() }
        }
    }
}
