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

/** The Ingrédients note with the lines for [names] marked started, "Lait" becoming "Lait (entamé)". */
fun withStartedIngredients(ingredientsNote: String, groups: List<CourseGroup>, names: List<String>): String {
    if (names.isEmpty()) return ingredientsNote
    val keys = names.map { it.itemKey() }.toSet()
    val lines = presentIngredients(ingredientsNote).map { line ->
        if (line.ingredientLineKey() in keys) "${line.courseItemName()} $STARTED_SUFFIX" else line
    }
    return renderIngredientsNote(lines, groups)
}

private const val STARTED_SUFFIX = "(entamé)"

/** The Ingrédients note without the lines for [names], the rest re-sorted as the editor sorts it. */
fun withoutIngredients(ingredientsNote: String, groups: List<CourseGroup>, names: List<String>): String {
    val keys = names.map { it.itemKey() }.toSet()
    return renderIngredientsNote(presentIngredients(ingredientsNote).filter { it.ingredientLineKey() !in keys }, groups)
}
