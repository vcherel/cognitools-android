package com.example.myapp.workout

import org.json.JSONArray
import org.json.JSONObject

/** One screen of a session: an exercise card, or the rest before [next]. */
sealed interface SessionStep {
    val round: Int

    /** [slot] is the exercise's place among all the session's exercises, its dot on the path. */
    data class Work(val exercise: Exercise, override val round: Int, val slot: Int) : SessionStep

    data class Rest(val seconds: Int, val next: Exercise, override val round: Int, val nextSlot: Int) : SessionStep
}

/** A rest after every exercise, the longer one between rounds, none after the last exercise. */
fun sessionSteps(program: WorkoutProgram): List<SessionStep> {
    val steps = mutableListOf<SessionStep>()
    val exercises = program.exercises
    var slot = 0
    for (round in 0 until program.rounds) {
        exercises.forEachIndexed { index, exercise ->
            if (slot > 0) {
                val seconds = if (index == 0) program.roundRestSeconds else program.restSeconds
                if (seconds > 0) steps += SessionStep.Rest(seconds, exercise, round, slot)
            }
            steps += SessionStep.Work(exercise, round, slot)
            slot++
        }
    }
    return steps
}

/** The last round is the boss, as soon as there is more than one. */
fun isBossRound(round: Int, program: WorkoutProgram) = program.rounds > 1 && round == program.rounds - 1

/** The parts an exercise card is cleared in: each side, or the full reps then each negative. */
fun partsOf(exercise: Exercise): Int = when (exercise.type) {
    ExerciseType.PerSide -> 2
    ExerciseType.Negatives -> 1 + exercise.negatives
    else -> 1
}

const val XP_PER_EXERCISE = 5

fun xpFor(round: Int, program: WorkoutProgram) = if (isBossRound(round, program)) XP_PER_EXERCISE * 2 else XP_PER_EXERCISE

fun sessionXp(program: WorkoutProgram): Int =
    (0 until program.rounds).sumOf { xpFor(it, program) * program.exercises.size }

/** [into] XP gathered toward the next level, out of [needed]. */
data class Level(val level: Int, val into: Int, val needed: Int)

/** Going from level n to n + 1 takes 100 (n + 1) XP. */
fun levelOf(totalXp: Int): Level {
    var level = 1
    var left = totalXp
    while (left >= 100 * (level + 1)) {
        left -= 100 * (level + 1)
        level++
    }
    return Level(level, left, 100 * (level + 1))
}

private val BOSSES = listOf(
    "The Iron Warden",
    "The Gravity Lord",
    "The Stone Golem",
    "The Rust Knight",
    "The Tide Titan",
    "The Granite Hydra"
)

fun bossName(level: Int) = BOSSES[(level - 1).mod(BOSSES.size)]

/**
 * A session in progress, saved on every change so a killed process resumes it. [stepStartedAt]
 * drives the rest and timed countdowns from the wall clock; [extraRest] is what +15 s added to the
 * current rest; [roundEnds] holds when each finished round ended.
 */
data class SessionProgress(
    val startedAt: Long,
    val step: Int = 0,
    val part: Int = 0,
    val stepStartedAt: Long = startedAt,
    val extraRest: Int = 0,
    val roundEnds: List<Long> = emptyList(),
    val finishedAt: Long? = null
)

/** The progress once the current part is cleared at [now]: the next part, step, or the end. */
fun SessionProgress.advance(steps: List<SessionStep>, now: Long): SessionProgress {
    val current = steps[step]
    if (current is SessionStep.Work && part + 1 < partsOf(current.exercise)) return copy(part = part + 1, stepStartedAt = now)
    val next = step + 1
    val roundOver = next == steps.size || steps[next].round != current.round
    val ends = if (roundOver && current is SessionStep.Work) roundEnds + now else roundEnds
    return if (next == steps.size) {
        copy(roundEnds = ends, finishedAt = now)
    } else {
        copy(step = next, part = 0, stepStartedAt = now, extraRest = 0, roundEnds = ends)
    }
}

fun SessionProgress.toJson(): String = JSONObject()
    .put("startedAt", startedAt)
    .put("step", step)
    .put("part", part)
    .put("stepStartedAt", stepStartedAt)
    .put("extraRest", extraRest)
    .put("roundEnds", JSONArray(roundEnds))
    .apply { finishedAt?.let { put("finishedAt", it) } }
    .toString()

fun sessionFromJson(text: String): SessionProgress? = runCatching {
    val o = JSONObject(text)
    val ends = o.getJSONArray("roundEnds")
    SessionProgress(
        startedAt = o.getLong("startedAt"),
        step = o.getInt("step"),
        part = o.getInt("part"),
        stepStartedAt = o.getLong("stepStartedAt"),
        extraRest = o.getInt("extraRest"),
        roundEnds = (0 until ends.length()).map { ends.getLong(it) },
        finishedAt = if (o.has("finishedAt")) o.getLong("finishedAt") else null
    )
}.getOrNull()

/** How long each round took, from the session start and the round ends. */
fun SessionProgress.roundDurations(): List<Long> =
    roundEnds.mapIndexed { i, end -> end - (if (i == 0) startedAt else roundEnds[i - 1]) }
