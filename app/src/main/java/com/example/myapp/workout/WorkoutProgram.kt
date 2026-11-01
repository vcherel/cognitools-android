package com.example.myapp.workout

import org.json.JSONArray
import org.json.JSONObject

/**
 * How an exercise card runs. [Timed] counts [Exercise.seconds] down, [PerSide] asks for each side in
 * turn, [Negatives] has the full reps first then [Exercise.negatives] paced descents of
 * [Exercise.seconds] each.
 */
enum class ExerciseType { Reps, Timed, PerSide, Negatives }

data class Exercise(
    val name: String,
    val target: String,
    val type: ExerciseType = ExerciseType.Reps,
    val seconds: Int = 0,
    val negatives: Int = 0
)

data class WorkoutProgram(
    val exercises: List<Exercise>,
    val rounds: Int = 3,
    val restSeconds: Int = 45,
    val roundRestSeconds: Int = 120
) {
    companion object {
        val DEFAULT = WorkoutProgram(
            listOf(
                Exercise("Pull ups", "2 pull ups, 3 slow negatives", ExerciseType.Negatives, seconds = 5, negatives = 3),
                Exercise("Push ups", "Max minus 2"),
                Exercise("Squats", "15 to 20"),
                Exercise("Single leg deadlift", "8 per leg", ExerciseType.PerSide),
                Exercise("Plank", "30 to 45 s", ExerciseType.Timed, seconds = 45),
                Exercise("Bird dog", "10 per side", ExerciseType.PerSide)
            )
        )
    }
}

fun WorkoutProgram.toJson(): String = JSONObject()
    .put("exercises", JSONArray(exercises.map {
        JSONObject()
            .put("name", it.name)
            .put("target", it.target)
            .put("type", it.type.name)
            .put("seconds", it.seconds)
            .put("negatives", it.negatives)
    }))
    .put("rounds", rounds)
    .put("restSeconds", restSeconds)
    .put("roundRestSeconds", roundRestSeconds)
    .toString()

fun programFromJson(text: String?): WorkoutProgram = text?.let {
    runCatching {
        val root = JSONObject(it)
        val list = root.getJSONArray("exercises")
        WorkoutProgram(
            exercises = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                Exercise(
                    name = o.getString("name"),
                    target = o.optString("target"),
                    type = ExerciseType.valueOf(o.optString("type", ExerciseType.Reps.name)),
                    seconds = o.optInt("seconds"),
                    negatives = o.optInt("negatives")
                )
            },
            rounds = root.getInt("rounds"),
            restSeconds = root.getInt("restSeconds"),
            roundRestSeconds = root.getInt("roundRestSeconds")
        )
    }.getOrNull()
} ?: WorkoutProgram.DEFAULT
