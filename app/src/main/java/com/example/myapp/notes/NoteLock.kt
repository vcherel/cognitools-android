package com.example.myapp.notes

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.noteLockDataStore by preferencesDataStore("note_lock")
private val SALT_KEY = stringPreferencesKey("pin_salt")
private val HASH_KEY = stringPreferencesKey("pin_hash")

// The sentinel file that resets the lock from the CLI without touching notes:
//   adb shell touch /sdcard/Android/data/<pkg>/files/reset_lock
private const val RESET_SENTINEL = "reset_lock"

/**
 * The notes PIN store. Gate only: the PIN never encrypts note content, it just
 * hides it behind an unlock prompt. A salted PBKDF2 hash of the PIN lives in a
 * DataStore file; the raw PIN is never stored.
 */
object NoteLock {
    suspend fun hasPin(context: Context): Boolean {
        val prefs = context.noteLockDataStore.data.first()
        return prefs[SALT_KEY] != null && prefs[HASH_KEY] != null
    }

    suspend fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        context.noteLockDataStore.edit {
            it[SALT_KEY] = Base64.encodeToString(salt, Base64.NO_WRAP)
            it[HASH_KEY] = hash(pin, salt)
        }
    }

    suspend fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = context.noteLockDataStore.data.first()
        val saltB64 = prefs[SALT_KEY] ?: return false
        val expected = prefs[HASH_KEY] ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        return constantTimeEquals(hash(pin, salt), expected)
    }

    suspend fun clearPin(context: Context) {
        context.noteLockDataStore.edit {
            it.remove(SALT_KEY)
            it.remove(HASH_KEY)
        }
    }

    /**
     * CLI escape hatch. If the reset sentinel file is present, clears the PIN,
     * unlocks every note, and deletes the sentinel. No note is otherwise touched.
     */
    suspend fun applyResetSentinelIfPresent(context: Context, dao: NoteDao) {
        val file = File(context.getExternalFilesDir(null), RESET_SENTINEL)
        if (!file.exists()) return
        clearPin(context)
        dao.unlockAll()
        file.delete()
    }

    private suspend fun hash(pin: String, salt: ByteArray): String = withContext(Dispatchers.Default) {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        Base64.encodeToString(factory.generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var r = 0
        for (i in x.indices) r = r or (x[i].toInt() xor y[i].toInt())
        return r == 0
    }
}

enum class PinPurpose { Create, Enter }

/**
 * PIN prompt. In [PinPurpose.Create] mode it asks for a code then a confirmation
 * and stores it; in [PinPurpose.Enter] mode it verifies against the stored code.
 * [onSuccess] fires only once the PIN is set (Create) or verified (Enter).
 */
@Composable
fun PinDialog(
    purpose: PinPurpose,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(confirming) { focusRequester.requestFocus() }

    val title = when {
        purpose == PinPurpose.Enter -> "Code de déverrouillage"
        confirming -> "Confirme le code"
        else -> "Choisis un code"
    }

    fun submit() {
        error = null
        when (purpose) {
            PinPurpose.Enter -> scope.launch {
                if (NoteLock.verifyPin(context, pin)) onSuccess()
                else {
                    error = "Code incorrect"
                    pin = ""
                }
            }
            PinPurpose.Create -> if (!confirming) {
                if (pin.length < 4) error = "4 chiffres minimum" else confirming = true
            } else if (confirm == pin) {
                scope.launch {
                    NoteLock.setPin(context, pin)
                    onSuccess()
                }
            } else {
                error = "Les codes ne correspondent pas"
                pin = ""
                confirm = ""
                confirming = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = if (confirming) confirm else pin,
                    onValueChange = { new ->
                        val digits = new.filter { it.isDigit() }.take(8)
                        if (confirming) confirm = digits else pin = digits
                        error = null
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester)
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
