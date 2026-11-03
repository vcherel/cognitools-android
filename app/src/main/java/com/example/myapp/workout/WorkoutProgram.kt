package com.example.myapp.workout

import org.json.JSONArray
import org.json.JSONObject

/** How an exercise card runs: [Timed] counts [Exercise.seconds] down once started, [Reps] is one Done. */
enum class ExerciseType { Reps, Timed }

data class Exercise(
    val name: String,
    val target: String,
    val type: ExerciseType = ExerciseType.Reps,
    val seconds: Int = 0
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
                Exercise("Pull ups", "2 pull ups, 3 slow negatives"),
                Exercise("Push ups", "Max minus 2"),
                Exercise("Scapular pull ups", "8 to 12, arms straight, shoulders down"),
                Exercise("Pike push ups", "6 to 10, hips high"),
                Exercise("Superman", "Face down, arms and legs lifted", ExerciseType.Timed, seconds = 20),
                Exercise("Chair dips", "8 to 12, elbows back"),
                Exercise("Dead bug", "8 per side, lower back on the floor"),
                Exercise("Plank", "30 to 45 s", ExerciseType.Timed, seconds = 45)
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
                    type = ExerciseType.entries.find { t -> t.name == o.optString("type") } ?: ExerciseType.Reps,
                    seconds = o.optInt("seconds")
                )
            },
            rounds = root.getInt("rounds"),
            restSeconds = root.getInt("restSeconds"),
            roundRestSeconds = root.getInt("roundRestSeconds")
        )
    }.getOrNull()
} ?: WorkoutProgram.DEFAULT
