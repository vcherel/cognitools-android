package com.example.myapp.recipes

import com.example.myapp.notes.CourseGroup
import com.example.myapp.notes.UNCHECKED_PREFIX
import com.example.myapp.notes.courseGroupIndexOf
import com.example.myapp.notes.courseItemName
import com.example.myapp.notes.ingredientKey
import com.example.myapp.notes.ingredientLineKey
import com.example.myapp.notes.insertCourseLine
import com.example.myapp.notes.itemKey
import com.example.myapp.notes.presentCourseLines
import com.example.myapp.notes.presentIngredients
import com.example.myapp.notes.renderIngredientsNote

// The pure text side of what a recipe does to the Ingrédients and Courses notes, on top of IngredientSync.

/** The keys of what the Ingrédients note lists, "Lait (entamé)" counting as "lait". */
fun stockKeys(ingredientsNote: String): Set<String> =
    presentIngredients(ingredientsNote).map { it.ingredientLineKey() }.toSet()

/**
 * The Courses note with [names] added, each under its model name in its section, or under Autres when
 * the model does not know it. A name already on the list, checked or not, is left alone.
 */
fun withCourseItems(courses: String, groups: List<CourseGroup>, names: List<String>): String {
    val listed = presentCourseLines(courses).map { it.ingredientKey() }.toMutableSet()
    var content = courses
    for (name in names) {
        val groupIndex = courseGroupIndexOf(groups, name)
        val canonical = groups.getOrNull(groupIndex)?.items?.first { it.itemKey() == name.itemKey() } ?: name.trim()
        if (!listed.add(canonical.itemKey())) continue
        content = insertCourseLine(content, groups, groupIndex, UNCHECKED_PREFIX + canonical)
    }
    return content
}

/**
 * The Ingrédients note with the comment of each line named in [comments] replaced, "Lait" becoming
 * "Lait (entamé)". A blank comment leaves the bare name.
 */
fun withCommentedIngredients(ingredientsNote: String, groups: List<CourseGroup>, comments: Map<String, String>): String {
    if (comments.isEmpty()) return ingredientsNote
    val byKey = comments.mapKeys { it.key.itemKey() }
    val lines = presentIngredients(ingredientsNote).map { line ->
        byKey[line.ingredientLineKey()]?.let { line.withIngredientComment(it) } ?: line
    }
    return renderIngredientsNote(lines, groups)
}

/** An Ingrédients line with its "(...)" comment replaced by [comment], or dropped when blank. */
fun String.withIngredientComment(comment: String): String =
    comment.trim().let { if (it.isEmpty()) courseItemName() else "${courseItemName()} ($it)" }

/** The comment an Ingrédients line carries in parentheses, "entamé" for "Lait (entamé)". */
fun ingredientComment(line: String): String = COMMENT.find(line)?.groupValues?.get(1)?.trim().orEmpty()

private val COMMENT = Regex("""\(([^)]*)\)""")

val COMMENT_CHOICES = listOf("entamé", "presque fini", "à finir vite", "au congélateur")

/** The comma separated parts of a comment, "entamé" and "au congélateur" for "entamé, au congélateur". */
fun commentParts(comment: String): List<String> = comment.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** [comment] with [choice] added, or taken out when it already holds it. */
fun toggledComment(comment: String, choice: String): String {
    val parts = commentParts(comment)
    return (if (choice in parts) parts - choice else parts + choice).joinToString(", ")
}

/** The Ingrédients note without the lines for [names], the rest re-sorted as the editor sorts it. */
fun withoutIngredients(ingredientsNote: String, groups: List<CourseGroup>, names: List<String>): String {
    val keys = names.map { it.itemKey() }.toSet()
    return renderIngredientsNote(presentIngredients(ingredientsNote).filter { it.ingredientLineKey() !in keys }, groups)
}
