package com.example.myapp.notes

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything the note editor does across the Courses / Ingrédients / model notes: moving items
 * from one to the other, adding one directly, re-sorting a note against its model, and reconciling
 * the items the model doesn't know about. The pure text side of all this lives in IngredientSync.
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
     * are spliced in at their canonical spot and removed from Courses right away; items not in the
     * model are queued for the reconcile dialog.
     */
    fun sendCheckedToIngredients() {
        scope.launch {
            val notes = dao.getNotes()
            val ingredientsNote = notes.withTitle(INGREDIENTS_TITLE) ?: return@launch showMissingNote(INGREDIENTS_TITLE)
            val modelNote = notes.withTitle(INGREDIENT_MODEL_TITLE) ?: return@launch showMissingNote(INGREDIENT_MODEL_TITLE)

            val checkedLines = content.split("\n").filter { it.isCheckedLine() }
            if (checkedLines.isEmpty()) return@launch

            val groups = parseIngredientGroups(modelNote.content)
            val flatModel = groups.flatten()
            val modelKeys = flatModel.map { it.trim().lowercase() }

            val matchedNames = mutableListOf<String>()
            val matchedLines = mutableListOf<String>()
            val unknown = mutableListOf<ReconcileItem>()
            for (line in checkedLines) {
                val modelIndex = modelKeys.indexOf(line.ingredientKey())
                if (modelIndex >= 0) {
                    matchedNames.add(flatModel[modelIndex])
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
                val newPresent = (present + matchedNames).distinctBy { it.trim().lowercase() }
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
                    movedCount = matchedLines.size
                )
            )
        }
    }

    /**
     * Removes a checkbox line from the Ingrédients note and inserts it as a new unchecked item in
     * its canonical section of the Courses note, so running out of an ingredient turns straight
     * into a correctly placed shopping list entry. Items the Courses model doesn't know land in a
     * trailing "Autres" section rather than being lost.
     */
    fun moveLineToCourses(index: Int) {
        val removed = content.split("\n")[index]
        scope.launch {
            val notes = dao.getNotes()
            val courses = notes.withTitle(COURSES_TITLE) ?: return@launch showMissingNote(COURSES_TITLE)
            val modelNote = notes.withTitle(COURSES_MODEL_TITLE)
            val itemName = removed.checkboxText().withoutQuantitySuffix().trim()

            val lines = content.split("\n").toMutableList()
            lines.removeAt(index)
            saveContent(lines.joinToString("\n"))

            val addedLine = UNCHECKED_PREFIX + itemName
            val newCoursesContent = if (modelNote != null) {
                val groups = parseCourseGroups(modelNote.content)
                insertCourseLine(courses.content, groups, courseGroupIndexOf(groups, itemName), addedLine)
            } else {
                if (courses.content.isEmpty()) addedLine else courses.content + "\n" + addedLine
            }
            dao.upsertNote(courses.copy(content = newCoursesContent, updatedAt = System.currentTimeMillis()))

            if (showUndoSnackbar("Déplacé vers Courses")) {
                val current = content.split("\n").toMutableList()
                current.add(index.coerceAtMost(current.size), removed)
                saveContent(current.joinToString("\n"))

                val latestCourses = dao.getNote(courses.id) ?: return@launch
                val coursesLines = latestCourses.content.split("\n").toMutableList()
                val addedIndex = coursesLines.indexOf(addedLine)
                if (addedIndex >= 0) {
                    coursesLines.removeAt(addedIndex)
                    dao.upsertNote(
                        latestCourses.copy(
                            content = coursesLines.joinToString("\n"),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    /**
     * Adds a single ingredient directly from the Ingrédients note: same matching against the model
     * as the Courses flow, but with no Courses line to remove.
     */
    fun addIngredientDirectly(name: String) {
        scope.launch {
            val modelNote = dao.getNotes().withTitle(INGREDIENT_MODEL_TITLE)
                ?: return@launch showMissingNote(INGREDIENT_MODEL_TITLE)
            val groups = parseIngredientGroups(modelNote.content)
            val flatModel = groups.flatten()
            val snapshot = content
            val modelIndex = flatModel.map { it.trim().lowercase() }.indexOf(name.trim().lowercase())

            val pending = if (modelIndex >= 0) {
                val present = presentIngredients(content)
                val newPresent = (present + flatModel[modelIndex]).distinctBy { it.trim().lowercase() }
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
     * Re-sorts the ingredients already present in the Ingrédients note by the model's groups and
     * alphabetical order. Called from the model note, which is the one currently open.
     */
    fun resortIngredients() {
        scope.launch {
            val ingredientsNote = dao.getNotes().withTitle(INGREDIENTS_TITLE)
                ?: return@launch showMissingNote(INGREDIENTS_TITLE)
            val groups = parseIngredientGroups(content)
            val modelKeys = groups.flatten().map { it.trim().lowercase() }
            val present = presentIngredients(ingredientsNote.content)
            val unknown = present.filter { it.trim().lowercase() !in modelKeys }

            val newContent = renderIntoIngredientsNote(ingredientsNote.content, present, groups)
            if (newContent != ingredientsNote.content) {
                dao.upsertNote(ingredientsNote.copy(content = newContent, updatedAt = System.currentTimeMillis()))
            }

            startBatch(
                NoteSyncBatch(
                    targetId = ingredientsNote.id,
                    modelId = noteId,
                    sourceId = null,
                    sourceSnapshot = null,
                    targetSnapshot = ingredientsNote.content,
                    modelSnapshot = content,
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
            val modelNote = dao.getNotes().withTitle(COURSES_MODEL_TITLE)
                ?: return@launch showMissingNote(COURSES_MODEL_TITLE)
            val groups = parseCourseGroups(modelNote.content)
            val groupIndex = courseGroupIndexOf(groups, name)
            val snapshot = content

            val pending = if (groupIndex < groups.size) {
                val canonical = groups[groupIndex].items.first { it.trim().lowercase() == name.trim().lowercase() }
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
                    groups = groups.map { it.items },
                    groupNames = groups.map { it.name },
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
            val coursesNote = notes.withTitle(COURSES_TITLE) ?: return@launch showMissingNote(COURSES_TITLE)
            val modelNote = notes.withTitle(COURSES_MODEL_TITLE) ?: return@launch showMissingNote(COURSES_MODEL_TITLE)

            val coursesContent = if (coursesNote.id == noteId) content else coursesNote.content
            val modelContent = if (modelNote.id == noteId) content else modelNote.content

            val groups = parseCourseGroups(modelContent)
            val modelKeys = groups.flatMap { it.items }.map { it.trim().lowercase() }.toSet()
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
                    groups = groups.map { it.items },
                    groupNames = groups.map { it.name },
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
     * came from one. A [groupIndex] of -1 means a brand-new group (Ingrédients only).
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
            when (currentBatch.kind) {
                SyncKind.INGREDIENT -> {
                    val newGroups = parseIngredientGroups(newModelContent)
                    val present = presentIngredients(targetContent)
                    val newPresent = if (current.inTarget) present else (present + current.name).distinctBy { it.trim().lowercase() }
                    updateNoteContent(currentBatch.targetId, renderIntoIngredientsNote(targetContent, newPresent, newGroups))
                }
                SyncKind.COURSE -> {
                    val newGroups = parseCourseGroups(newModelContent)
                    val newTargetContent = if (current.inTarget) {
                        renderCoursesSection(presentCourseLines(targetContent), newGroups)
                    } else {
                        insertCourseLine(targetContent, newGroups, groupIndex, UNCHECKED_PREFIX + current.name)
                    }
                    updateNoteContent(currentBatch.targetId, newTargetContent)
                }
            }

            removeSourceLine(current)
            advancePending(moved = true)
        }
    }

    /**
     * Reconcile choice (Courses only): the current unknown item starts a brand-new named section
     * of Modèle courses, placed just before the group at [beforeIndex] (or at the end when the
     * index is past the last group), and lands in that new section of the Courses note.
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
            val newTargetContent = if (current.inTarget) {
                renderCoursesSection(presentCourseLines(targetContent), newGroups)
            } else {
                insertCourseLine(
                    targetContent,
                    newGroups,
                    courseGroupIndexOf(newGroups, current.name),
                    UNCHECKED_PREFIX + current.name
                )
            }
            updateNoteContent(currentBatch.targetId, newTargetContent)

            // The remaining unknown items are offered the new category too.
            batch = currentBatch.copy(
                groups = newGroups.map { it.items },
                groupNames = newGroups.map { it.name }
            )
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
                        present.filter { it.trim().lowercase() != current.name.trim().lowercase() }
                    } else present
                    val newPresent = (basePresent + canonical).distinctBy { it.trim().lowercase() }
                    updateNoteContent(currentBatch.targetId, renderIntoIngredientsNote(targetContent, newPresent, currentBatch.groups))
                }
                SyncKind.COURSE -> {
                    val groups = currentBatch.groups.mapIndexed { i, items ->
                        CourseGroup(currentBatch.groupNames?.getOrNull(i) ?: "", items)
                    }
                    val newTargetContent = if (current.inTarget) {
                        val relabeled = targetContent.split("\n").joinToString("\n") { raw ->
                            val trimmed = raw.trim()
                            if (trimmed.isCheckboxLine() && trimmed.ingredientKey() == current.name.trim().lowercase()) {
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
            val plural = if (count > 1) "s" else ""
            val noun = if (finished.kind == SyncKind.COURSE) "article" else "ingrédient"
            val verb = if (finished.reorder) "réordonné" else "ajouté"
            if (showUndoSnackbar("$count $noun$plural $verb$plural")) {
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

    private suspend fun showMissingNote(title: String) {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(
            message = "Note \"$title\" introuvable",
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
    }

    // Replaces any snackbar still showing instead of queueing behind it. True when Annuler was hit.
    private suspend fun showUndoSnackbar(message: String): Boolean {
        snackbar.currentSnackbarData?.dismiss()
        return snackbar.showSnackbar(
            message = message,
            actionLabel = "Annuler",
            withDismissAction = true,
            duration = SnackbarDuration.Short
        ) == SnackbarResult.ActionPerformed
    }
}

private fun List<Note>.withTitle(title: String): Note? =
    firstOrNull { it.title.trim().equals(title, ignoreCase = true) }

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
