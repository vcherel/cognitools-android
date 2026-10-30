package com.example.myapp.journal

import android.content.Context
import com.example.myapp.mailRepository
import com.example.myapp.userMessage
import com.example.myapp.writeAtomically
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The daily journal the laptop writes (~/Documents/Code/daily-journal). Issues arrive as files in the
 * Yahoo mailbox's Journal folder and are kept in filesDir; the feedback given on one is written next
 * to it and sent back to the same folder a few seconds after the last change. Dates that still have
 * feedback to send survive a killed process in [pendingFile] and go out at the next refresh. Nothing
 * is fetched unless asked: the screen's refresh button is the only caller of [refresh].
 */
class JournalRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "journal").apply { mkdirs() }
    private val pendingFile = File(dir, "pending.txt")
    private val readFile = File(dir, "read.txt")
    private val positionsFile = File(dir, "positions.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushLock = Mutex()
    private val positionsLock = Mutex()
    private var pushJob: Job? = null

    private val _issues = MutableStateFlow<List<JournalIssue>>(emptyList())
    /** Newest first. */
    val issues: StateFlow<List<JournalIssue>> = _issues.asStateFlow()

    private val _feedback = MutableStateFlow<Map<String, JournalFeedback>>(emptyMap())
    val feedback: StateFlow<Map<String, JournalFeedback>> = _feedback.asStateFlow()

    private val _read = MutableStateFlow<Set<String>>(emptySet())
    /** Dates of the issues marked as read. */
    val read: StateFlow<Set<String>> = _read.asStateFlow()

    /** Whether any issue on the phone is still unread, which lights the menu tile. */
    val unread: StateFlow<Boolean> = combine(_issues, _read) { issues, read -> issues.any { it.date !in read } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _positions = MutableStateFlow(JournalPositions())
    val positions: StateFlow<JournalPositions> = _positions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch {
            if (positionsFile.exists()) {
                runCatching { parsePositions(positionsFile.readText()) }.getOrNull()?.let { _positions.value = it }
            }
            loadLocal()
        }
    }

    private fun issueFile(date: String) = File(dir, "journal_$date.json")
    private fun feedbackFile(date: String) = File(dir, "feedback_$date.json")

    private fun loadLocal() {
        val files = dir.listFiles { f -> f.name.startsWith("journal_") && f.name.endsWith(".json") }.orEmpty()
            .sortedByDescending { it.name }
        files.drop(KEEP_ISSUES).forEach { stale ->
            stale.delete()
            feedbackFile(stale.name.removePrefix("journal_").removeSuffix(".json")).delete()
        }
        val issues = files.take(KEEP_ISSUES).mapNotNull { runCatching { parseIssue(it.readText()) }.getOrNull() }
        val dates = issues.map { it.date }.toSet()
        val read = if (readFile.exists()) readFile.readLines().filter { it in dates }.toSet() else emptySet()
        readFile.writeAtomically(read.joinToString("\n"))
        _read.value = read
        _positions.update { p -> p.copy(last = p.last?.takeIf { it in dates }, byDate = p.byDate.filterKeys { it in dates }) }
        _issues.value = issues
        _feedback.value = issues.mapNotNull { issue ->
            feedbackFile(issue.date).takeIf { it.exists() }
                ?.let { runCatching { parseFeedback(it.readText()) }.getOrNull() }
                ?.let { issue.date to it }
        }.toMap()
    }

    fun clearError() {
        _error.value = null
    }

    /** Fetches the issues not on the phone yet, then sends whatever feedback is still waiting. */
    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        scope.launch {
            try {
                val known = _issues.value.map { "journal_${it.date}.json" }.toSet()
                val fresh = appContext.mailRepository.readFiles(FOLDER, "journal_", known)
                fresh.forEach { (name, json) -> File(dir, name).writeAtomically(json) }
                if (fresh.isNotEmpty()) loadLocal()
                push()
            } catch (e: Exception) {
                _error.value = userMessage(e)
            } finally {
                _loading.value = false
            }
        }
    }

    fun toggleRead(date: String) {
        val read = _read.updateAndGet { if (date in it) it - date else it + date }
        scope.launch { readFile.writeAtomically(read.joinToString("\n")) }
    }

    /** The issue to open: the one left last, unless a newer one has arrived and is still unread. */
    fun startDate(): String? {
        val newest = _issues.value.firstOrNull()?.date ?: return null
        val last = _positions.value.last ?: return newest
        return if (newest > last && newest !in _read.value) newest else last
    }

    fun setShown(date: String) = editPositions { it.copy(last = date) }

    fun saveScroll(date: String, index: Int, offset: Int) = editPositions { p ->
        val position = p.byDate[date] ?: JournalPosition()
        p.copy(byDate = p.byDate + (date to position.copy(index = index, offset = offset)))
    }

    fun toggleExpanded(date: String, itemId: String) = editPositions { p ->
        val position = p.byDate[date] ?: JournalPosition()
        val expanded = if (itemId in position.expanded) position.expanded - itemId else position.expanded + itemId
        p.copy(byDate = p.byDate + (date to position.copy(expanded = expanded)))
    }

    /** Writes the latest value under the lock, so writes landing out of order still leave the newest on disk. */
    private fun editPositions(change: (JournalPositions) -> JournalPositions) {
        val before = _positions.value
        if (_positions.updateAndGet(change) == before) return
        scope.launch { positionsLock.withLock { positionsFile.writeAtomically(_positions.value.toJson()) } }
    }

    /** Tapping the rating already given takes it back. */
    fun rate(date: String, itemId: String, rating: Int) = edit(date) { fb ->
        val ratings = if (fb.ratings[itemId] == rating) fb.ratings - itemId else fb.ratings + (itemId to rating)
        fb.copy(ratings = ratings)
    }

    fun setNote(date: String, itemId: String, note: String) = edit(date) { fb ->
        fb.copy(notes = if (note.isBlank()) fb.notes - itemId else fb.notes + (itemId to note.trim()))
    }

    fun answerIdea(date: String, ideaId: String, answer: IdeaAnswer) = edit(date) { fb ->
        fb.copy(ideas = if (fb.ideas[ideaId] == answer) fb.ideas - ideaId else fb.ideas + (ideaId to answer))
    }

    fun markOpened(date: String, itemId: String) {
        if (itemId in _feedback.value[date]?.opened.orEmpty()) return
        edit(date) { it.copy(opened = it.opened + itemId) }
    }

    fun setGeneral(date: String, text: String) = edit(date) { it.copy(general = text.trim()) }

    private fun edit(date: String, change: (JournalFeedback) -> JournalFeedback) {
        var updated = JournalFeedback()
        _feedback.update { all ->
            updated = change(all[date] ?: JournalFeedback())
            all + (date to updated)
        }
        scope.launch {
            pushLock.withLock {
                feedbackFile(date).writeAtomically(updated.toJson(date))
                pendingFile.writeAtomically((pending() + date).joinToString("\n"))
            }
        }
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            runCatching { push() }
        }
    }

    private fun pending(): Set<String> =
        if (pendingFile.exists()) pendingFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    private suspend fun push() = pushLock.withLock {
        for (date in pending()) {
            val file = feedbackFile(date)
            if (file.exists()) appContext.mailRepository.putFile(FOLDER, "feedback_$date.json", file.readText())
            pendingFile.writeAtomically((pending() - date).joinToString("\n"))
        }
    }

    private companion object {
        const val FOLDER = "Journal"
        /** As many as the laptop leaves in the mailbox (KEEP_ON_MAILBOX in daily-journal's main.py). */
        const val KEEP_ISSUES = 14
        const val PUSH_DELAY_MS = 4_000L
    }
}
