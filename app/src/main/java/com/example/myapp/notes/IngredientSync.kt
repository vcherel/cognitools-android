package com.example.myapp.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapp.AppDialog
import java.text.Collator
import java.util.Locale

// The "Courses" <-> "Ingrédients" flow, plus adding/reordering items directly on either
// note. The Ingrédients note is a free-text recipe prompt whose ingredient names sit
// between an "Ingrédients :" header and a "Contraintes :" section, one per line with a
// blank line between logical groups. The "Modèle ingrédients" note holds the canonical
// ingredients, also one per line with blank-line groups, giving both the group layout and,
// within each group, membership. Ingredients are always kept sorted alphabetically inside
// their group.
//
// The Courses note follows the same idea but stays a checkbox list: the "Modèle courses"
// note holds the canonical shopping order as named sections (one "--- Nom" separator per
// section), and Courses gets laid out with a labeled section per group, each sorted
// alphabetically, preserving every line's checked state and "(N)" quantity. Unlike the
// Ingrédients note, single additions to Courses are inserted surgically into their section
// rather than triggering a full re-render, so manual drag reordering elsewhere survives;
// only the explicit "reorder" action rebuilds the whole note from the model.

const val INGREDIENTS_TITLE = "Ingrédients"
const val INGREDIENT_MODEL_TITLE = "Modèle ingrédients"
const val COURSES_TITLE = "Courses"
const val COURSES_MODEL_TITLE = "Modèle courses"

enum class SyncKind { INGREDIENT, COURSE }

// French-aware, case- and accent-insensitive ordering for the alphabetical sorting.
private val frenchCollator: Collator = Collator.getInstance(Locale.FRENCH).apply {
    strength = Collator.SECONDARY
}
private val byFrenchName = Comparator<String> { a, b -> frenchCollator.compare(a.trim(), b.trim()) }

/** An unknown item awaiting reconciliation: its own (possibly misspelled) name, the
 *  source line to remove once resolved (null when there's no source note in this flow),
 *  and whether it is already a line in the target note (true when re-sorting in place). */
data class ReconcileItem(
    val name: String,
    val sourceLine: String?,
    val inTarget: Boolean
)

/**
 * In-flight state for a sync batch (a Courses<->Ingrédients move, a direct add, or a model
 * re-sort, for either note). Holds snapshots of every touched note so the whole batch can
 * be undone at once, the current model groups for the reconcile picker (with their names
 * for Courses), the unknown items still to reconcile, the count of items added, and
 * whether this batch was a pure reorder.
 */
data class NoteSyncBatch(
    val targetId: String,
    val modelId: String,
    val sourceId: String?,
    val sourceSnapshot: String?,
    val targetSnapshot: String,
    val modelSnapshot: String,
    val groups: List<List<String>>,
    val groupNames: List<String>? = null,
    val pending: List<ReconcileItem>,
    val movedCount: Int,
    val reorder: Boolean = false,
    val kind: SyncKind = SyncKind.INGREDIENT
)

/** Canonical key for matching an item against the model: no checkbox prefix, no "(N)"
 *  quantity, trimmed, case-insensitive. */
fun String.ingredientKey(): String = checkboxText().withoutQuantitySuffix().trim().lowercase()

/** The model split into groups by blank lines (and separators). Each inner list is one
 *  group's ingredient names in order; blank runs separate groups. */
fun parseIngredientGroups(content: String): List<List<String>> {
    val groups = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    for (raw in content.split("\n")) {
        val t = raw.trim()
        if (t.isEmpty() || t.isSeparatorLine()) {
            if (current.isNotEmpty()) { groups.add(current); current = mutableListOf() }
        } else current.add(t)
    }
    if (current.isNotEmpty()) groups.add(current)
    return groups
}

/** Flat list of every model ingredient, groups concatenated top to bottom. */
fun parseIngredientModel(content: String): List<String> = parseIngredientGroups(content).flatten()

/** Index of the group that contains `name` (case-insensitive), or -1. */
fun ingredientGroupIndexOf(groups: List<List<String>>, name: String): Int {
    val k = name.trim().lowercase()
    return groups.indexOfFirst { g -> g.any { it.trim().lowercase() == k } }
}

/** Content with the first occurrence of each given line removed. */
fun removeFirstLines(content: String, toRemove: List<String>): String {
    val lines = content.split("\n").toMutableList()
    for (r in toRemove) {
        val i = lines.indexOf(r)
        if (i >= 0) lines.removeAt(i)
    }
    return lines.joinToString("\n")
}

// The [start, end) line range holding the ingredient list: after the "Ingrédients :"
// header up to the "Contraintes" line (or the end of the note).
private fun ingredientRegion(lines: List<String>): Pair<Int, Int> {
    val header = lines.indexOfFirst { it.lowercase().contains("ingrédients") }
    val start = header + 1
    val end = (start until lines.size).firstOrNull { lines[it].lowercase().contains("contraintes") } ?: lines.size
    return start to end
}

/** The ingredient names currently listed in the prompt (one per line, blanks skipped). */
fun presentIngredients(noteContent: String): List<String> {
    val lines = noteContent.split("\n")
    val (start, end) = ingredientRegion(lines)
    return lines.subList(start.coerceIn(0, lines.size), end.coerceIn(start.coerceIn(0, lines.size), lines.size))
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.isSeparatorLine() }
}

/**
 * Lays out the present ingredients grouped and ordered by the model: one block per model
 * group (blank line between blocks), each block sorted alphabetically. Names not found in
 * any model group are kept together in a trailing block so nothing is ever lost.
 */
fun renderIngredientSection(present: List<String>, groups: List<List<String>>): String {
    val canonical = HashMap<String, String>()
    for (g in groups) for (n in g) canonical.putIfAbsent(n.trim().lowercase(), n.trim())
    val distinct = present.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    val blocks = mutableListOf<String>()
    for (g in groups) {
        val keys = g.map { it.trim().lowercase() }.toSet()
        val members = distinct.filter { it.lowercase() in keys }
            .map { canonical[it.lowercase()] ?: it }
            .sortedWith(byFrenchName)
        if (members.isNotEmpty()) blocks.add(members.joinToString("\n"))
    }
    val known = groups.flatten().map { it.trim().lowercase() }.toSet()
    val unknown = distinct.filter { it.lowercase() !in known }.sortedWith(byFrenchName)
    if (unknown.isNotEmpty()) blocks.add(unknown.joinToString("\n"))
    return blocks.joinToString("\n\n")
}

/**
 * Rewrites the prompt's ingredient list with `present` rendered by renderIngredientSection,
 * leaving the header, the Contraintes section and the blank spacing around the list intact.
 */
fun renderIntoIngredientsNote(noteContent: String, present: List<String>, groups: List<List<String>>): String {
    val lines = noteContent.split("\n")
    val (start, end) = ingredientRegion(lines)
    var lead = start.coerceIn(0, lines.size)
    while (lead < end && lines[lead].isBlank()) lead++
    var trail = end.coerceIn(lead, lines.size)
    while (trail > lead && lines[trail - 1].isBlank()) trail--

    val section = renderIngredientSection(present, groups)
    val rebuilt = buildList {
        addAll(lines.subList(0, lead))
        if (section.isNotEmpty()) addAll(section.split("\n"))
        addAll(lines.subList(trail, lines.size))
    }
    return rebuilt.joinToString("\n")
}

/** One named section of the Modèle courses note: a shop-order group (e.g. "Crèmerie") and
 *  the canonical item names it contains. */
data class CourseGroup(val name: String, val items: List<String>)

/** The Modèle courses note split into named groups: each "--- Nom" separator starts a new
 *  group that runs until the next separator. Lines before the first separator, if any,
 *  form an unnamed leading group. */
fun parseCourseGroups(content: String): List<CourseGroup> {
    val groups = mutableListOf<CourseGroup>()
    var name = ""
    var current = mutableListOf<String>()
    for (raw in content.split("\n")) {
        val t = raw.trim()
        if (t.isEmpty()) continue
        if (t.isSeparatorLine()) {
            if (current.isNotEmpty()) { groups.add(CourseGroup(name, current)); current = mutableListOf() }
            name = t.separatorName()
        } else current.add(t)
    }
    if (current.isNotEmpty()) groups.add(CourseGroup(name, current))
    return groups
}

/** Index of the Courses model group containing `name` (case-insensitive), or `groups.size`
 *  as a virtual trailing "Autres" slot when no group has it. */
fun courseGroupIndexOf(groups: List<CourseGroup>, name: String): Int {
    val k = name.trim().lowercase()
    val i = groups.indexOfFirst { g -> g.items.any { it.trim().lowercase() == k } }
    return if (i >= 0) i else groups.size
}

/** Every checkbox line currently in the Courses note (separators and blanks skipped). */
fun presentCourseLines(content: String): List<String> =
    content.split("\n").map { it.trim() }.filter { it.isCheckboxLine() }

private val byFrenchCourseLine = Comparator<String> { a, b ->
    frenchCollator.compare(a.ingredientKey(), b.ingredientKey())
}

/** A checkbox line with its item name replaced, keeping its checkbox state and "(N)"
 *  quantity suffix intact. */
fun String.withItemName(newName: String): String {
    val prefix = if (isCheckedLine()) CHECKED_PREFIX else UNCHECKED_PREFIX
    val qty = checkboxText().itemQuantity()
    return prefix + newName + (if (qty > 1) " ($qty)" else "")
}

/**
 * Lays out the present Courses lines grouped and ordered by the model: one labeled section
 * per Modèle courses group, in that group's canonical order, each section sorted
 * alphabetically by item name. Lines whose name isn't in any group land in a trailing
 * "Autres" section so nothing is ever lost.
 */
fun renderCoursesSection(present: List<String>, groups: List<CourseGroup>): String {
    val distinct = present.distinctBy { it.ingredientKey() }
    val blocks = mutableListOf<String>()
    for (g in groups) {
        val keys = g.items.map { it.trim().lowercase() }.toSet()
        val members = distinct.filter { it.ingredientKey() in keys }.sortedWith(byFrenchCourseLine)
        if (members.isNotEmpty()) {
            val header = if (g.name.isNotEmpty()) SEPARATOR_PREFIX + g.name else "---"
            blocks.add((listOf(header) + members).joinToString("\n"))
        }
    }
    val known = groups.flatMap { it.items }.map { it.trim().lowercase() }.toSet()
    val unknown = distinct.filter { it.ingredientKey() !in known }.sortedWith(byFrenchCourseLine)
    if (unknown.isNotEmpty()) blocks.add((listOf(SEPARATOR_PREFIX + "Autres") + unknown).joinToString("\n"))
    return blocks.joinToString("\n")
}

private data class CourseSection(val headerLine: Int, val groupIdx: Int, val range: IntRange)

/**
 * Inserts a single new Courses checkbox line into its canonical section (by group index,
 * or `groups.size` for the trailing "Autres" section), without touching any other line, so
 * manual reordering elsewhere in the note survives. Creates the section if it isn't present
 * yet, positioned relative to the sections that are, per the model's group order.
 */
fun insertCourseLine(content: String, groups: List<CourseGroup>, groupIndex: Int, line: String): String {
    val lines = content.split("\n").filter { it.isNotBlank() }.toMutableList()
    val headerName = groups.getOrNull(groupIndex)?.name ?: "Autres"

    val headerIdxs = lines.indices.filter { lines[it].trim().isSeparatorLine() }
    val sections = headerIdxs.mapIndexed { i, hi ->
        val name = lines[hi].trim().separatorName()
        val gi = groups.indexOfFirst { it.name.equals(name, ignoreCase = true) }.let { if (it >= 0) it else groups.size }
        val end = headerIdxs.getOrNull(i + 1) ?: lines.size
        CourseSection(hi, gi, (hi + 1) until end)
    }

    val existing = sections.firstOrNull { it.groupIdx == groupIndex }
    if (existing != null) {
        var insertAt = existing.range.last + 1
        for (idx in existing.range) {
            if (byFrenchCourseLine.compare(line, lines[idx]) < 0) { insertAt = idx; break }
        }
        lines.add(insertAt, line)
        return lines.joinToString("\n")
    }

    val header = if (headerName.isNotEmpty()) SEPARATOR_PREFIX + headerName else "---"
    val insertBefore = sections.firstOrNull { it.groupIdx > groupIndex }
    val at = insertBefore?.headerLine ?: lines.size
    lines.addAll(at, listOf(header, line))
    return lines.joinToString("\n")
}

/** Inserts `name` alphabetically into the model's group at `groupIndex` (a blank-line
 *  separated block), leaving the rest of the model verbatim. Falls back to a new group
 *  when the index is out of range. */
fun addNameToModelGroup(modelContent: String, groupIndex: Int, name: String): String {
    val lines = modelContent.split("\n").toMutableList()
    val blocks = mutableListOf<IntRange>()
    var i = 0
    while (i < lines.size) {
        val t = lines[i].trim()
        if (t.isEmpty() || t.isSeparatorLine()) { i++; continue }
        val s = i
        while (i < lines.size && lines[i].trim().isNotEmpty() && !lines[i].trim().isSeparatorLine()) i++
        blocks.add(s until i)
    }
    val block = blocks.getOrNull(groupIndex) ?: return appendModelGroup(modelContent, name)
    var insertAt = block.last + 1
    for (idx in block) {
        if (byFrenchName.compare(name, lines[idx].trim()) < 0) { insertAt = idx; break }
    }
    lines.add(insertAt, name)
    return lines.joinToString("\n")
}

/** Adds `name` as a brand-new group at the bottom of the model. */
fun appendModelGroup(modelContent: String, name: String): String {
    val trimmed = modelContent.trimEnd('\n')
    return if (trimmed.isBlank()) name else "$trimmed\n\n$name"
}

// Levenshtein edit distance, for ranking model entries by closeness to a mistyped item.
private fun levenshtein(a: String, b: String): Int {
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val tmp = dp[j]
            dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = tmp
        }
    }
    return dp[b.length]
}

/** Model entries ordered by closeness to `query`: substring matches first, then by
 *  edit distance, then alphabetically. */
fun rankIngredientsByCloseness(model: List<String>, query: String): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return model
    return model.sortedWith(
        compareBy(
            { if (it.lowercase().contains(q)) 0 else 1 },
            { levenshtein(q, it.lowercase()) },
            { it.lowercase() }
        )
    )
}

private enum class ReconcileStep { Choose, Group, Existing }

/**
 * Shown for an item whose name isn't in the model. Lets the user add it as a new entry
 * (choosing which group, or a new group when `allowNewGroup`), map it to an existing model
 * entry (for a misspelling or variant spelling), or skip it. Within the chosen group the
 * entry is placed alphabetically, so only the group needs picking. `groupLabels`, when
 * given, names each group in the picker instead of listing its members.
 */
@Composable
fun IngredientReconcileDialog(
    itemName: String,
    groups: List<List<String>>,
    groupLabels: List<String>? = null,
    allowNewGroup: Boolean = true,
    onAddNew: (groupIndex: Int) -> Unit,
    onMapExisting: (String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember(itemName) { mutableStateOf(ReconcileStep.Choose) }

    AppDialog(onDismiss = onDismiss) {
        when (step) {
            ReconcileStep.Choose -> {
                Text("« $itemName »", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cet article n'est pas reconnu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                ChoiceRow("Ajouter au modèle", Icons.Default.Add) {
                    step = ReconcileStep.Group
                }
                ChoiceRow("C'est déjà dans le modèle", Icons.Default.Edit) {
                    step = ReconcileStep.Existing
                }
                ChoiceRow("Ignorer", Icons.Default.Close, onSkip)
            }

            ReconcileStep.Group -> {
                Text("Dans quel groupe ?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "« $itemName » sera placé par ordre alphabétique dans le groupe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(groups) { i, g ->
                        PositionRow(groupLabels?.getOrNull(i)?.ifEmpty { null } ?: g.joinToString(", ")) { onAddNew(i) }
                        HorizontalDivider()
                    }
                    if (allowNewGroup) {
                        item {
                            PositionRow("Nouveau groupe", bold = true) { onAddNew(-1) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BackRow { step = ReconcileStep.Choose }
            }

            ReconcileStep.Existing -> {
                var query by remember { mutableStateOf("") }
                Text("Lequel ?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                val ranked = rankIngredientsByCloseness(groups.flatten(), query.ifBlank { itemName })
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(ranked) { ingredient ->
                        PositionRow(ingredient) { onMapExisting(ingredient) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BackRow { step = ReconcileStep.Choose }
            }
        }
    }
}

/** Simple name prompt for adding an item directly to the Ingrédients or Courses note. */
@Composable
fun AddIngredientNameDialog(
    title: String = "Ajouter un ingrédient",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AppDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Nom") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Annuler") }
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Ajouter") }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PositionRow(label: String, bold: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun BackRow(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text("Retour")
    }
}
