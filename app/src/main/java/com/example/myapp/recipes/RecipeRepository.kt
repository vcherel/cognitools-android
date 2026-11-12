package com.example.myapp.recipes

import android.content.Context
import com.example.myapp.BACKGROUND_SEND_DELAY_MS
import com.example.myapp.flashcards.AppDatabase
import com.example.myapp.mailRepository
import com.example.myapp.notes.COURSES_MODEL_TITLE
import com.example.myapp.notes.COURSES_TITLE
import com.example.myapp.notes.INGREDIENTS_TITLE
import com.example.myapp.notes.Note
import com.example.myapp.notes.parseCourseGroups
import com.example.myapp.notes.presentIngredients
import com.example.myapp.notesLists
import com.example.myapp.retryUntilSent
import com.example.myapp.userMessage
import com.example.myapp.writeAtomically
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The recipe the laptop's `/recipes` skill picked (~/Documents/Code/daily-journal), and what cooking it
 * does to the notes. The laptop proposes from a snapshot of the Ingrédients and Modèle courses notes,
 * uploaded to the mailbox's Journal folder a few seconds after either changes, retried until it goes
 * through and sent at once when the app goes to the background. Only the latest recipe is kept, fetched on refresh.
 */
class RecipeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "recipes").apply { mkdirs() }
    private val recipeFile = File(dir, "recipe.json")
    private val doneFile = File(dir, "done.txt")
    private val sentFile = File(dir, "sent.json")
    private val commentsDir = File(dir, "comments").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    @Volatile private var latestSnapshot: String? = null
    private val dao = AppDatabase.get(appContext).noteDao()

    private val _recipe = MutableStateFlow<Recipe?>(null)
    val recipe: StateFlow<Recipe?> = _recipe.asStateFlow()

    private val _done = MutableStateFlow<Set<Int>>(emptySet())
    /** The steps of the current recipe ticked off. */
    val done: StateFlow<Set<Int>> = _done.asStateFlow()

    /** The lines of the Ingrédients note, what the Cooked dialog can add beyond the recipe. */
    val pantry: StateFlow<List<String>> = appContext.notesLists.notes.filterNotNull()
        .map { notes -> notes.titled(INGREDIENTS_TITLE)?.let { presentIngredients(it.content) } ?: emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The keys of what the Ingrédients note lists, what a recipe ingredient is checked against. */
    val stock: StateFlow<Set<String>> = appContext.notesLists.notes.filterNotNull()
        .map { notes -> notes.titled(INGREDIENTS_TITLE)?.let { stockKeys(it.content) } ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val timers = RecipeTimers(appContext, dir)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch {
            if (recipeFile.exists()) _recipe.value = runCatching { parseRecipe(recipeFile.readText()) }.getOrNull()
            val lines = if (doneFile.exists()) doneFile.readLines() else emptyList()
            if (lines.firstOrNull() == _recipe.value?.id) _done.value = lines.drop(1).mapNotNull { it.toIntOrNull() }.toSet()
        }
        scope.launch {
            appContext.notesLists.notes.filterNotNull()
                .map(::snapshot)
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { snapshot ->
                    latestSnapshot = snapshot
                    delay(UPLOAD_DELAY_MS)
                    retryUntilSent { upload(snapshot) }
                }
        }
    }

    /** Sends the snapshot now, as the app goes to the background: the process may die before the delay ends. */
    fun sendNow() {
        scope.launch {
            delay(BACKGROUND_SEND_DELAY_MS)
            latestSnapshot?.let { runCatching { upload(it) } }
        }
    }

    private fun List<Note>.titled(title: String): Note? =
        firstOrNull { it.title.trim().equals(title, ignoreCase = true) }

    private fun snapshot(notes: List<Note>): String? {
        val ingredients = notes.titled(INGREDIENTS_TITLE) ?: return null
        return snapshotJson(ingredients.content, notes.titled(COURSES_MODEL_TITLE)?.content.orEmpty())
    }

    private suspend fun upload(snapshot: String) = uploadLock.withLock {
        if (sentFile.exists() && sentFile.readText() == snapshot) return@withLock
        appContext.mailRepository.putFile(FOLDER, SNAPSHOT_NAME, snapshot)
        sentFile.writeAtomically(snapshot)
    }

    fun clearError() {
        _error.value = null
    }

    /** Fetches the recipe the laptop sent last, then sends the snapshot if the last upload failed. */
    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        scope.launch {
            try {
                val known = _recipe.value?.let { setOf("recipe_${it.id}.json") }.orEmpty()
                val fresh = appContext.mailRepository.readFiles(FOLDER, "recipe_", known)
                fresh.maxByOrNull { it.key }?.let { (_, json) ->
                    val recipe = parseRecipe(json)
                    recipeFile.writeAtomically(json)
                    doneFile.delete()
                    _done.value = emptySet()
                    _recipe.value = recipe
                }
                snapshot(dao.getNotes())?.let { upload(it) }
                sendComments()
            } catch (e: Exception) {
                _error.value = userMessage(e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Sends what Valentin thought of [recipe] to the laptop, which the `/recipes` skill reads before
     * proposing. Kept in `comments/` until the upload succeeds, retried on refresh.
     */
    fun comment(recipe: Recipe, text: String) {
        val json = JSONObject()
            .put("id", recipe.id)
            .put("title", recipe.title)
            .put("date", LocalDate.now().toString())
            .put("comment", text.trim())
            .toString()
        scope.launch {
            File(commentsDir, "cooked_${recipe.id}.json").writeAtomically(json)
            runCatching { sendComments() }
        }
    }

    private suspend fun sendComments() = uploadLock.withLock {
        commentsDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            appContext.mailRepository.putFile(FOLDER, file.name, file.readText())
            file.delete()
        }
    }

    fun toggleStep(index: Int) {
        val id = _recipe.value?.id ?: return
        val done = _done.updateAndGet { if (index in it) it - index else it + index }
        scope.launch { doneFile.writeAtomically((listOf(id) + done.sorted().map(Int::toString)).joinToString("\n")) }
    }

    /** The notes' contents before a change, by note id, what its undo puts back. */
    class Undo(val contents: Map<String, String>)

    /** Puts [names] on the Courses note. Null when there was nothing to add or a note is missing. */
    suspend fun addToCourses(names: List<String>): Undo? = edit(delete = emptyList(), comments = emptyMap(), groceries = names)

    /**
     * Removes [delete] from Ingrédients, sets the [comments] of the lines kept there and puts
     * [groceries] on Courses, in one undoable change.
     */
    suspend fun edit(delete: List<String>, comments: Map<String, String>, groceries: List<String>): Undo? {
        val notes = dao.getNotes()
        val ingredients = notes.titled(INGREDIENTS_TITLE) ?: return null
        val courses = notes.titled(COURSES_TITLE) ?: return null
        val groups = parseCourseGroups(notes.titled(COURSES_MODEL_TITLE)?.content.orEmpty())
        val before = mapOf(ingredients.id to ingredients.content, courses.id to courses.content)
        val pantry = if (delete.isEmpty() && comments.isEmpty()) ingredients.content
        else withCommentedIngredients(withoutIngredients(ingredients.content, groups, delete), groups, comments)
        val changed = listOf(
            ingredients to pantry,
            courses to withCourseItems(courses.content, groups, groceries)
        ).filter { (note, content) -> note.content != content }
        if (changed.isEmpty()) return null
        changed.forEach { (note, content) -> dao.upsertNote(note.copy(content = content, updatedAt = System.currentTimeMillis())) }
        return Undo(before)
    }

    suspend fun undo(undo: Undo) {
        for ((id, content) in undo.contents) {
            val note = dao.getNote(id) ?: continue
            dao.upsertNote(note.copy(content = content, updatedAt = System.currentTimeMillis()))
        }
    }

    private companion object {
        const val FOLDER = "Journal"
        const val SNAPSHOT_NAME = "ingredients.json"
        const val UPLOAD_DELAY_MS = 5_000L
    }
}
