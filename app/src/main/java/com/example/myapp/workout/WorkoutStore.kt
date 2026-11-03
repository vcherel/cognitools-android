package com.example.myapp.workout

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
private val RECORDS = stringPreferencesKey("records")
private val PROGRESS_RESET = booleanPreferencesKey("progress_reset_1")

/** The program, the XP and the session times in the workout DataStore, the session in progress in its own file. */
object WorkoutStore {
    fun program(context: Context): Flow<WorkoutProgram> =
        context.workoutDataStore.data.map { programFromJson(it[PROGRAM]) }

    suspend fun setProgram(context: Context, program: WorkoutProgram) {
        context.workoutDataStore.edit { it[PROGRAM] = program.toJson() }
    }

    fun xp(context: Context): Flow<Int> = context.workoutDataStore.data.map { it[XP] ?: 0 }

    fun records(context: Context): Flow<List<SessionRecord>> =
        context.workoutDataStore.data.map { recordsFromJson(it[RECORDS]) }

    /**
     * Adds the session's XP and time, and logs it as done, which moves the reminder past the
     * recovery days.
     */
    suspend fun finish(context: Context, xp: Int, record: SessionRecord) {
        context.workoutDataStore.edit {
            it[XP] = (it[XP] ?: 0) + xp
            it[RECORDS] = recordsToJson(recordsFromJson(it[RECORDS]) + record)
        }
        WorkoutReminder.markDone(context)
        clearSession(context)
    }

    /**
     * Wipes the XP, the records and the session in progress once, and puts the saved program back
     * to the default, for the switch to logged sets and the upper body program (Nov 2026).
     */
    suspend fun resetProgressOnce(context: Context) {
        var wiped = false
        context.workoutDataStore.edit {
            if (it[PROGRESS_RESET] != true) {
                it.remove(PROGRAM)
                it.remove(XP)
                it.remove(RECORDS)
                it[PROGRESS_RESET] = true
                wiped = true
            }
        }
        if (wiped) clearSession(context)
    }

    private fun sessionFile(context: Context) = File(context.filesDir, "workout_session.json")

    /** The session in progress, unless it was started on a program since edited. */
    suspend fun loadSession(context: Context, program: WorkoutProgram): SessionProgress? = withContext(Dispatchers.IO) {
        val file = sessionFile(context)
        if (!file.exists()) return@withContext null
        sessionFromJson(file.readText())?.takeIf { it.programKey == program.recordKey() }
    }

    suspend fun saveSession(context: Context, progress: SessionProgress) = withContext(Dispatchers.IO) {
        sessionFile(context).writeAtomically(progress.toJson())
    }

    suspend fun clearSession(context: Context) = withContext(Dispatchers.IO) {
        sessionFile(context).delete()
        Unit
    }
}
