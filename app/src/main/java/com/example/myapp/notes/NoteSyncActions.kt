package com.example.myapp.notes

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.myapp.showUndoSnackbar
import com.example.myapp.plural
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything the note editor does across the Courses / Ingrédients / Modèle courses notes: moving
 * items from one to the other, adding one directly, re-sorting a note against the model, and
 * reconciling the items the model doesn't know about. The pure text side of all this lives in
 * IngredientSync.
 *
 * The note currently open is read from [textFieldState] and written through [saveContent], so the
 * screen reflects a change immediately; every other note goes straight through the DAO.
 */
class NoteSyncActions(
    private val noteId: String,
    private val dao: NoteDao,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val textFieldState: TextFieldState,
    private val saveContent: (String) -> Unit
) {
    /** The batch being reconciled, which drives the reconcile dialog; null when there is none. */
    var batch by mutableStateOf<NoteSyncBatch?>(null)
        private set

    private val content: String get() = textFieldState.text.toString()

    /**
     * Moves every checked Courses item into the Ingrédients prompt. Items found in the model note
     * are spliced in at their canonical spot and removed from Courses right away (a non food item
     * is only removed, the fridge doesn't hold toilet paper); items not in the model are queued for
     * the reconcile dialog.
     */
    fun sendCheckedToIngredients() {
        scope.launch {
            val notes = dao.getNotes()
            val ingredientsNote = notes.findOrReport(INGREDIENTS_TITLE) ?: return@launch
            val modelNote = notes.findOrReport(COURSES_MODEL_TITLE) ?: return@launch

            val checkedLines = content.split("\n").filter { it.isCheckedLine() }
            if (checkedLines.isEmpty()) return@launch

            val groups = parseCourseGroups(modelNote.content)

            val matchedNames = mutableListOf<String>()
            val matchedLines = mutableListOf<String>()
            val unknown = mutableListOf<ReconcileItem>()
            for (line in checkedLines) {
                val groupIndex = courseGroupIndexOf(groups, line.ingredientKey())
                if (groupIndex < groups.size) {
                    val group = groups[groupIndex]
                    if (group.food) matchedNames.add(group.items.first { it.itemKey() == line.ingredientKey() })
                    matchedLines.add(line)
                } else {
                    unknown.add(
                        ReconcileItem(
                            name = line.checkboxText().withoutQuantitySuffix().trim(),
                            sourceLine = line,
                            inTarget = false
                        )
                    )
                }
            }

            val coursesSnapshot = content
            val ingredientsSnapshot = ingredientsNote.content

            if (matchedNames.isNotEmpty()) {
                val present = presentIngredients(ingredientsNote.content)
                val newPresent = (present + matchedNames).distinctBy { it.itemKey() }
                updateNoteContent(ingredientsNote.id, renderIntoIngredientsNote(ingredientsNote.content, newPresent, groups))
            }
            if (matchedLines.isNotEmpty()) {
                saveContent(dropEmptyCourseSections(removeFirstLines(content, matchedLines)))
            }

            startBatch(
                NoteSyncBatch(
                    targetId = ingredientsNote.id,
                    modelId = modelNote.id,
                    sourceId = noteId,
                    sourceSnapshot = coursesSnapshot,
                    targetSnapshot = ingredientsSnapshot,
                    modelSnapshot = modelNote.content,
                    groups = groups,
                    pending = unknown,
                    movedCount = matchedNames.size
                )
            )
        }
    }

    /**
     * Removes a checkbox line from the Ingrédients note and inserts it as a new unchecked item in
     * its canonical section of the Courses note, so running out of an ingredient turns straight
     * into a correctly placed shopping list entry. An item Modèle courses doesn't know goes through
     * the same reconcile dialog as a direct add, so it gets a real section instead of silently
     * landing in a trailing "Autres"; its Ingrédients line only disappears once that is answered.
     */
    fun moveLineToCourses(index: Int) {
        val removed = content.split("\n")[index]
        scope.launch {
            val notes = dao.getNotes()
            val courses = notes.findOrReport(COURSES_TITLE) ?: return@launch
            val modelNote = notes.findOrReport(COURSES_MODEL_TITLE) ?: return@launch
            val itemName = removed.checkboxText().withoutQuantitySuffix().trim()

            val groups = parseCourseGroups(modelNote.content)
            val groupIndex = courseGroupIndexOf(groups, itemName)
            val known = groupIndex < groups.size
            val ingredientsSnapshot = content

            if (known) {
                val lines = content.split("\n").toMutableList()
                lines.removeAt(index)
                saveContent(lines.joinToString("\n"))
                val canonical = groups[groupIndex].items.first { it.itemKey() == itemName.itemKey() }
                updateNoteContent(
                    courses.id,
                    insertCourseLine(courses.content, groups, groupIndex, UNCHECKED_PREFIX + canonical)
                )
            }

            startBatch(
                NoteSyncBatch(
                    targetId = courses.id,
                    modelId = modelNote.id,
                    sourceId = noteId,
                    sourceSnapshot = ingredientsSnapshot,
                    targetSnapshot = courses.content,
                    modelSnapshot = modelNote.content,
                    groups = groups,
                    pending = if (known) emptyList()
                    else listOf(ReconcileItem(name = itemName, sourceLine = removed, inTarget = false)),
                    movedCount = if (known) 1 else 0,
                    kind = SyncKind.COURSE
                )
            )
        }
    }

    /**
     * Adds a single ingredient directly from the Ingrédients note: same matching against the model
     * as the Courses flow, but with no Courses line to remove.
     */
    fun addIngredientDirectly(name: String) {
        scope.launch {
            val modelNote = dao.getNotes().findOrReport(COURSES_MODEL_TITLE) ?: return@launch
            val groups = parseCourseGroups(modelNote.content)
            val flatModel = groups.allItems()
            val snapshot = content
            val modelIndex = flatModel.map { it.itemKey() }.indexOf(name.itemKey())

            val pending = if (modelIndex >= 0) {
                val present = presentIngredients(content)
                val newPresent = (present + flatModel[modelIndex]).distinctBy { it.itemKey() }
                saveContent(renderIntoIngredientsNote(content, newPresent, groups))
                emptyList()
            } else {
                listOf(ReconcileItem(name = name, sourceLine = null, inTarget = false))
            }

            startBatch(
                NoteSyncBatch(
                    targetId = noteId,
                    modelId = modelNote.id,
                    sourceId = null,
                    sourceSnapshot = null,
                    targetSnapshot = snapshot,
                    modelSnapshot = modelNote.content,
                    groups = groups,
                    pending = pending,
                    movedCount = if (modelIndex >= 0) 1 else 0
                )
            )
        }
    }

    /**
     * Re-sorts the ingredients already present in the Ingrédients note by the model's food groups
     * and alphabetical order. Callable from either note: whichever one is open is read from and
     * written through the live text field.
     */
    fun resortIngredients() {
        scope.launch {
            val notes = dao.getNotes()
            val ingredientsNote = notes.findOrReport(INGREDIENTS_TITLE) ?: return@launch
            val modelNote = notes.findOrReport(COURSES_MODEL_TITLE) ?: return@launch

            val ingredientsContent = if (ingredientsNote.id == noteId) content else ingredientsNote.content
            val modelContent = if (modelNote.id == noteId) content else modelNote.content

            val groups = parseCourseGroups(modelContent)
            val modelKeys = groups.allItems().map { it.itemKey() }
            val present = presentIngredients(ingredientsContent)
            val unknown = present.filter { it.itemKey() !in modelKeys }

            val newContent = renderIntoIngredientsNote(ingredientsContent, present, groups)
            if (newContent != ingredientsContent) {
                updateNoteContent(ingredientsNote.id, newContent)
            }

            startBatch(
                NoteSyncBatch(
                    targetId = ingredientsNote.id,
                    modelId = modelNote.id,
                    sourceId = null,
                    sourceSnapshot = null,
                    targetSnapshot = ingredientsContent,
                    modelSnapshot = modelContent,
                    groups = groups,
                    pending = unknown.map { ReconcileItem(name = it, sourceLine = null, inTarget = true) },
                    movedCount = present.size - unknown.size,
                    reorder = true
                )
            )
        }
    }

    /**
     * Adds a single article to the Courses note: matched against Modèle courses and inserted
     * surgically into its section so the rest of the list is undisturbed; unmatched names go
     * through the reconcile dialog to pick a section.
     */
    fun addCourseItem(name: String) {
        scope.launch {
            val modelNote = dao.getNotes().findOrReport(COURSES_MODEL_TITLE) ?: return@launch
            val groups = parseCourseGroups(modelNote.content)
            val groupIndex = courseGroupIndexOf(groups, name)
            val snapshot = content

            val pending = if (groupIndex < groups.size) {
                val canonical = groups[groupIndex].items.first { it.itemKey() == name.itemKey() }
                saveContent(insertCourseLine(content, groups, groupIndex, UNCHECKED_PREFIX + canonical))
                emptyList()
            } else {
                listOf(ReconcileItem(name = name, sourceLine = null, inTarget = false))
            }

            startBatch(
                NoteSyncBatch(
                    targetId = noteId,
                    modelId = modelNote.id,
                    sourceId = null,
                    sourceSnapshot = null,
                    targetSnapshot = snapshot,
                    modelSnapshot = modelNote.content,
                    groups = groups,
                    pending = pending,
                    movedCount = if (pending.isEmpty()) 1 else 0,
                    kind = SyncKind.COURSE
                )
            )
        }
    }

    /**
     * Re-sorts the Courses note into labeled sections per Modèle courses group, sorted
     * alphabetically within each, preserving checked state and quantities. Callable from either
     * note: whichever one is open is read from and written through the live text field.
     */
    fun resortCourses() {
        scope.launch {
            val notes = dao.getNotes()
            val coursesNote = notes.findOrReport(COURSES_TITLE) ?: return@launch
            val modelNote = notes.findOrReport(COURSES_MODEL_TITLE) ?: return@launch

            val coursesContent = if (coursesNote.id == noteId) content else coursesNote.content
            val modelContent = if (modelNote.id == noteId) content else modelNote.content

            val groups = parseCourseGroups(modelContent)
            val modelKeys = groups.allItems().map { it.itemKey() }.toSet()
            val present = presentCourseLines(coursesContent)
            val unknown = present.filter { it.ingredientKey() !in modelKeys }

            val newCoursesContent = renderCoursesSection(present, groups)
            if (newCoursesContent != coursesContent) {
                updateNoteContent(coursesNote.id, newCoursesContent)
            }

            startBatch(
                NoteSyncBatch(
                    targetId = coursesNote.id,
                    modelId = modelNote.id,
                    sourceId = null,
                    sourceSnapshot = null,
                    targetSnapshot = coursesContent,
                    modelSnapshot = modelContent,
                    groups = groups,
                    pending = unknown.map {
                        ReconcileItem(name = it.checkboxText().withoutQuantitySuffix().trim(), sourceLine = null, inTarget = true)
                    },
                    movedCount = present.size - unknown.size,
                    reorder = true,
                    kind = SyncKind.COURSE
                )
            )
        }
    }

    /**
     * Reconcile choice: add the current unknown item to the model in the chosen group
     * (alphabetically placed), refresh the target note, and remove it from the source note if it
     * came from one.
     */
    fun reconcileAddNew(groupIndex: Int) {
        val currentBatch = batch ?: return
        val current = currentBatch.pending.firstOrNull() ?: return
        scope.launch {
            val modelNote = dao.getNote(currentBatch.modelId)
            val targetNote = dao.getNote(currentBatch.targetId)
            if (modelNote == null || targetNote == null || current.name.isEmpty()) {
                advancePending(moved = false)
                return@launch
            }
            val modelContent = if (currentBatch.modelId == noteId) content else modelNote.content
            val newModelContent = addNameToModelGroup(modelContent, groupIndex, current.name)
            updateNoteContent(currentBatch.modelId, newModelContent)

            val targetContent = if (currentBatch.targetId == noteId) content else targetNote.content
            val newGroups = parseCourseGroups(newModelContent)
            when (currentBatch.kind) {
                SyncKind.INGREDIENT -> {
                    val present = presentIngredients(targetContent)
                    val food = newGroups.getOrNull(groupIndex)?.food ?: true
                    val newPresent = if (current.inTarget || !food) present
                    else (present + current.name).distinctBy { it.itemKey() }
                    updateNoteContent(currentBatch.targetId, renderIntoIngredientsNote(targetContent, newPresent, newGroups))
                }
                SyncKind.COURSE -> {
                    val newTargetContent = if (current.inTarget) {
                        renderCoursesSection(presentCourseLines(targetContent), newGroups)
                    } else {
                        insertCourseLine(targetContent, newGroups, groupIndex, UNCHECKED_PREFIX + current.name)
                    }
                    updateNoteContent(currentBatch.targetId, newTargetContent)
                }
            }
            // A section created by the fallback is offered to the remaining unknown items too.
            batch = batch?.copy(groups = newGroups)

            removeSourceLine(current)
            advancePending(moved = true)
        }
    }

    /**
     * Reconcile choice: the current unknown item starts a brand-new named section of Modèle
     * courses, placed just before the group at [beforeIndex] (or at the end when the index is
     * past the last group), and lands in that new section of the target note.
     */
    fun reconcileAddNewCourseGroup(groupName: String, beforeIndex: Int) {
        val currentBatch = batch ?: return
        val current = currentBatch.pending.firstOrNull() ?: return
        scope.launch {
            val modelNote = dao.getNote(currentBatch.modelId)
            val targetNote = dao.getNote(currentBatch.targetId)
            if (modelNote == null || targetNote == null || current.name.isEmpty() || groupName.isEmpty()) {
                advancePending(moved = false)
                return@launch
            }
            val modelContent = if (currentBatch.modelId == noteId) content else modelNote.content
            val newModelContent = addCourseGroupToModel(modelContent, beforeIndex, groupName, current.name)
            updateNoteContent(currentBatch.modelId, newModelContent)

            val newGroups = parseCourseGroups(newModelContent)
            val targetContent = if (currentBatch.targetId == noteId) content else targetNote.content
            val newTargetContent = when (currentBatch.kind) {
                SyncKind.INGREDIENT -> {
                    val present = presentIngredients(targetContent)
                    val newPresent = if (current.inTarget) present
                    else (present + current.name).distinctBy { it.itemKey() }
                    renderIntoIngredientsNote(targetContent, newPresent, newGroups)
                }
                SyncKind.COURSE -> if (current.inTarget) {
                    renderCoursesSection(presentCourseLines(targetContent), newGroups)
                } else {
                    insertCourseLine(
                        targetContent,
                        newGroups,
                        courseGroupIndexOf(newGroups, current.name),
                        UNCHECKED_PREFIX + current.name
                    )
                }
            }
            updateNoteContent(currentBatch.targetId, newTargetContent)

            // The remaining unknown items are offered the new category too.
            batch = currentBatch.copy(groups = newGroups)
            removeSourceLine(current)
            advancePending(moved = true)
        }
    }

    /**
     * Reconcile choice: the current unknown item is really an existing model entry written
     * differently; splice that canonical name in and remove it from the source note if it came
     * from one.
     */
    fun reconcileMapExisting(canonical: String) {
        val currentBatch = batch ?: return
        val current = currentBatch.pending.firstOrNull() ?: return
        scope.launch {
            val targetNote = dao.getNote(currentBatch.targetId)
            if (targetNote == null) {
                advancePending(moved = false)
                return@launch
            }
            val targetContent = if (currentBatch.targetId == noteId) content else targetNote.content
            when (currentBatch.kind) {
                SyncKind.INGREDIENT -> {
                    val present = presentIngredients(targetContent)
                    val basePresent = if (current.inTarget) {
                        present.filter { it.itemKey() != current.name.itemKey() }
                    } else present
                    val newPresent = (basePresent + canonical).distinctBy { it.itemKey() }
                    updateNoteContent(currentBatch.targetId, renderIntoIngredientsNote(targetContent, newPresent, currentBatch.groups))
                }
                SyncKind.COURSE -> {
                    val groups = currentBatch.groups
                    val newTargetContent = if (current.inTarget) {
                        val relabeled = targetContent.split("\n").joinToString("\n") { raw ->
                            val trimmed = raw.trim()
                            if (trimmed.isCheckboxLine() && trimmed.ingredientKey() == current.name.itemKey()) {
                                trimmed.withItemName(canonical)
                            } else raw
                        }
                        renderCoursesSection(presentCourseLines(relabeled), groups)
                    } else {
                        insertCourseLine(targetContent, groups, courseGroupIndexOf(groups, canonical), UNCHECKED_PREFIX + canonical)
                    }
                    updateNoteContent(currentBatch.targetId, newTargetContent)
                }
            }

            removeSourceLine(current)
            advancePending(moved = true)
        }
    }

    /**
     * Reconcile choice: drop the item. It never reaches the target note, and the line it came
     * from (a checked Courses line) is removed, so ignoring an item while filling the fridge
     * takes it off the shopping list rather than leaving it checked there.
     */
    fun reconcileSkip() {
        batch?.pending?.firstOrNull()?.let { removeSourceLine(it) }
        advancePending(moved = false)
    }

    /** Stops reconciling: keep what is already resolved and leave the rest as is. */
    fun dismissBatch() {
        val current = batch ?: return
        batch = null
        finish(current.copy(pending = emptyList()))
    }

    // Opens the reconcile dialog on the first unknown item, or closes the batch out right away
    // when the model knew everything.
    private fun startBatch(newBatch: NoteSyncBatch) {
        if (newBatch.pending.isEmpty()) finish(newBatch) else batch = newBatch
    }

    // Drops the current item from the pending list and, once none remain, closes the batch and
    // shows its snackbar. `moved` counts the item toward the "X ajoutés" tally.
    private fun advancePending(moved: Boolean) {
        val current = batch ?: return
        val remaining = current.pending.drop(1)
        val updated = current.copy(
            pending = remaining,
            movedCount = current.movedCount + if (moved) 1 else 0
        )
        if (remaining.isEmpty()) {
            batch = null
            finish(updated)
        } else {
            batch = updated
        }
    }

    // Shows the "X ingrédients/articles ajoutés/réordonnés" snackbar, whose Annuler restores the
    // source, target and model notes to their snapshots.
    private fun finish(finished: NoteSyncBatch) {
        if (finished.movedCount == 0) return
        scope.launch {
            val count = finished.movedCount
            val noun = if (finished.kind == SyncKind.COURSE) "article" else "ingrédient"
            val verb = if (finished.reorder) "réordonné" else "ajouté"
            if (snackbar.showUndoSnackbar("$count $noun${plural(count)} $verb${plural(count)}")) {
                if (finished.sourceId != null && finished.sourceSnapshot != null) {
                    updateNoteContent(finished.sourceId, finished.sourceSnapshot)
                }
                updateNoteContent(finished.targetId, finished.targetSnapshot)
                updateNoteContent(finished.modelId, finished.modelSnapshot)
            }
        }
    }

    private fun removeSourceLine(item: ReconcileItem) {
        val line = item.sourceLine ?: return
        saveContent(dropEmptyCourseSections(removeFirstLines(content, listOf(line))))
    }

    // Writes new content to a note: through the live text field when it is the one currently open
    // (so the screen reflects it immediately), straight to the DAO otherwise.
    private suspend fun updateNoteContent(id: String, newContent: String) {
        if (id == noteId) {
            saveContent(newContent)
            return
        }
        val note = dao.getNote(id) ?: return
        if (note.content != newContent) {
            dao.upsertNote(note.copy(content = newContent, updatedAt = System.currentTimeMillis()))
        }
    }

    /** The note titled [title], or null after telling the user it is missing. */
    private suspend fun List<Note>.findOrReport(title: String): Note? {
        firstOrNull { it.title.trim().equals(title, ignoreCase = true) }?.let { return it }
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(
            message = "Note \"$title\" introuvable",
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        return null
    }
}

@Composable
fun rememberNoteSyncActions(
    noteId: String,
    dao: NoteDao,
    snackbar: SnackbarHostState,
    textFieldState: TextFieldState,
    saveContent: (String) -> Unit
): NoteSyncActions {
    val scope = rememberCoroutineScope()
    return remember { NoteSyncActions(noteId, dao, scope, snackbar, textFieldState, saveContent) }
}
