package com.example.myapp.notes

import android.content.Context
import com.example.myapp.mailRepository
import com.example.myapp.notesLists
import com.example.myapp.writeAtomically
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The Claude note's round trip with the laptop's `/claude-note` skill (~/Documents/Code/daily-journal).
 * The note goes to the mailbox's Journal folder a few seconds after it changes; the skill answers
 * with the lines it solved, one `claude_solved_<time>.json` per send, which the note's refresh
 * button removes from the note and then deletes from the mailbox.
 */
@OptIn(FlowPreview::class)
class ClaudeNoteSync(context: Context) {
    private val appContext = context.applicationContext
    private val sentFile = File(appContext.filesDir, "claude_note_sent.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()

    init {
        scope.launch {
            appContext.notesLists.notes.filterNotNull()
                .map { notes -> notes.firstOrNull { it.title.trim().equals(CLAUDE_NOTE_TITLE, ignoreCase = true) } }
                .filterNotNull()
                .map { it.content }
                .distinctUntilChanged()
                .debounce(UPLOAD_DELAY_MS)
                .collect { runCatching { upload(it) } }
        }
    }

    /** Sends the note unless the mailbox already holds this exact text. */
    suspend fun upload(content: String) = uploadLock.withLock {
        val snapshot = JSONObject().put("content", content).toString()
        if (sentFile.exists() && sentFile.readText() == snapshot) return@withLock
        appContext.mailRepository.putFile(FOLDER, SNAPSHOT_NAME, snapshot)
        sentFile.writeAtomically(snapshot)
    }

    /** The lines the laptop solved, oldest send first, and the files they came in. */
    class Solved(val lines: List<String>, val files: Set<String>)

    suspend fun fetchSolved(): Solved {
        val files = appContext.mailRepository.readFiles(FOLDER, SOLVED_PREFIX, emptySet())
        val lines = files.toSortedMap().values.flatMap { json ->
            val array = JSONObject(json).getJSONArray("lines")
            List(array.length()) { array.getString(it) }
        }
        return Solved(lines, files.keys)
    }

    suspend fun clearSolved(files: Set<String>) {
        if (files.isNotEmpty()) appContext.mailRepository.deleteFiles(FOLDER, files)
    }

    private companion object {
        const val FOLDER = "Journal"
        const val SNAPSHOT_NAME = "claude_note.json"
        const val SOLVED_PREFIX = "claude_solved_"
        const val UPLOAD_DELAY_MS = 5_000L
    }
}

/**
 * [content] without the first line equal to each of [solved] (spaces at either end ignored), and how
 * many went. A removal that leaves two blank lines side by side drops one of them.
 */
fun withSolvedRemoved(content: String, solved: List<String>): Pair<String, Int> {
    val lines = content.split("\n").toMutableList()
    var removed = 0
    for (target in solved.map { it.trim() }.filter { it.isNotEmpty() }) {
        val index = lines.indexOfFirst { it.trim() == target }
        if (index < 0) continue
        lines.removeAt(index)
        removed++
        if (index in 1 until lines.size && lines[index - 1].isBlank() && lines[index].isBlank()) lines.removeAt(index)
    }
    return lines.joinToString("\n") to removed
}
