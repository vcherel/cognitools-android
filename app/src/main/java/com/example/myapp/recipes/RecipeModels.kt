package com.example.myapp.recipes

import org.json.JSONObject

// The shapes below mirror `src/daily_journal/recipe.py` in ~/Documents/Code/daily-journal, which
// writes the recipes and reads the snapshot: a field changed here must change there.

/** [name] is the canonical Modèle courses name, matched against the Ingrédients note to tell what is missing. */
data class RecipeIngredient(val name: String, val quantity: String)

/** One recipe, [id] as yyyy-MM-dd-slug, which also names its file. */
data class Recipe(
    val id: String,
    val title: String,
    val pitch: String,
    val servings: Int,
    val minutes: Int,
    val ingredients: List<RecipeIngredient>,
    val steps: List<String>
)

fun parseRecipe(raw: String): Recipe {
    val obj = JSONObject(raw)
    val ingredients = obj.getJSONArray("ingredients")
    val steps = obj.getJSONArray("steps")
    return Recipe(
        id = obj.getString("id"),
        title = obj.getString("title"),
        pitch = obj.optString("pitch"),
        servings = obj.optInt("servings"),
        minutes = obj.optInt("minutes"),
        ingredients = (0 until ingredients.length()).map(ingredients::getJSONObject).map {
            RecipeIngredient(it.getString("name"), it.optString("quantity"))
        },
        steps = (0 until steps.length()).map(steps::getString)
    )
}

/** What the laptop proposes recipes from: the Ingrédients note (prompt and list) and the Modèle courses note. */
fun snapshotJson(ingredients: String, model: String): String = JSONObject().apply {
    put("ingredients", ingredients)
    put("model", model)
}.toString()
