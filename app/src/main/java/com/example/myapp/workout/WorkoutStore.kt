package com.example.myapp.workout

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapp.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val PROGRAM = stringPreferencesKey("program")
private val XP = intPreferencesKey("xp")

/** The program and the XP in the workout DataStore, the session in progress in its own file. */
object WorkoutStore {
    fun program(context: Context): Flow<WorkoutProgram> =
        context.workoutDataStore.data.map { programFromJson(it[PROGRAM]) }

    suspend fun setProgram(context: Context, program: WorkoutProgram) {
        context.workoutDataStore.edit { it[PROGRAM] = program.toJson() }
    }

    fun xp(context: Context): Flow<Int> = context.workoutDataStore.data.map { it[XP] ?: 0 }

    /** Adds the session's XP and logs it as done, which moves the reminder past the recovery days. */
    suspend fun finish(context: Context, xp: Int) {
        context.workoutDataStore.edit { it[XP] = (it[XP] ?: 0) + xp }
        WorkoutReminder.markDone(context)
        clearSession(context)
    }

    private fun sessionFile(context: Context) = File(context.filesDir, "workout_session.json")

    suspend fun loadSession(context: Context): SessionProgress? = withContext(Dispatchers.IO) {
        val file = sessionFile(context)
        if (!file.exists()) return@withContext null
        sessionFromJson(file.readText())
    }

    suspend fun saveSession(context: Context, progress: SessionProgress) = withContext(Dispatchers.IO) {
        sessionFile(context).writeAtomically(progress.toJson())
    }

    suspend fun clearSession(context: Context) = withContext(Dispatchers.IO) {
        sessionFile(context).delete()
        Unit
    }
}
