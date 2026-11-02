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
 * drives the rest countdown from the wall clock, [timerStartedAt] the timed exercise once its Start
 * is tapped; [extraRest] is what +15 s added to the current rest; [roundEnds] holds when each
 * finished round ended; [programKey] is the [recordKey] of the program it runs, so an edited
 * program starts a new session.
 */
data class SessionProgress(
    val startedAt: Long,
    val programKey: String,
    val step: Int = 0,
    val stepStartedAt: Long = startedAt,
    val timerStartedAt: Long? = null,
    val extraRest: Int = 0,
    val roundEnds: List<Long> = emptyList(),
    val finishedAt: Long? = null
)

/** The progress once the current step is cleared at [now]: the next step, or the end. */
fun SessionProgress.advance(steps: List<SessionStep>, now: Long): SessionProgress {
    val current = steps[step]
    val next = step + 1
    val roundOver = next == steps.size || steps[next].round != current.round
    val ends = if (roundOver && current is SessionStep.Work) roundEnds + now else roundEnds
    return if (next == steps.size) {
        copy(roundEnds = ends, finishedAt = now)
    } else {
        copy(step = next, stepStartedAt = now, timerStartedAt = null, extraRest = 0, roundEnds = ends)
    }
}

fun SessionProgress.toJson(): String = JSONObject()
    .put("startedAt", startedAt)
    .put("programKey", programKey)
    .put("step", step)
    .put("stepStartedAt", stepStartedAt)
    .put("extraRest", extraRest)
    .put("roundEnds", JSONArray(roundEnds))
    .apply { timerStartedAt?.let { put("timerStartedAt", it) } }
    .apply { finishedAt?.let { put("finishedAt", it) } }
    .toString()

fun sessionFromJson(text: String): SessionProgress? = runCatching {
    val o = JSONObject(text)
    val ends = o.getJSONArray("roundEnds")
    SessionProgress(
        startedAt = o.getLong("startedAt"),
        programKey = o.getString("programKey"),
        step = o.getInt("step"),
        stepStartedAt = o.getLong("stepStartedAt"),
        timerStartedAt = if (o.has("timerStartedAt")) o.getLong("timerStartedAt") else null,
        extraRest = o.getInt("extraRest"),
        roundEnds = (0 until ends.length()).map { ends.getLong(it) },
        finishedAt = if (o.has("finishedAt")) o.getLong("finishedAt") else null
    )
}.getOrNull()

/** How long each round took, from the session start and the round ends. */
fun SessionProgress.roundDurations(): List<Long> =
    roundEnds.mapIndexed { i, end -> end - (if (i == 0) startedAt else roundEnds[i - 1]) }

/** A finished session's time, [key] naming the program it ran, since only the same program compares. */
data class SessionRecord(val at: Long, val totalMs: Long, val key: String)

/** Everything that changes how long a session takes: the rounds, the rests and the exercises. */
fun WorkoutProgram.recordKey(): String =
    "$rounds/$restSeconds/$roundRestSeconds/" + exercises.joinToString("|") { "${it.name}:${it.type}:${it.seconds}" }

/** The sessions of [key] that beat every earlier one, newest (the current best) first. */
fun recordProgression(records: List<SessionRecord>, key: String): List<SessionRecord> {
    val beaten = mutableListOf<SessionRecord>()
    records.filter { it.key == key }.sortedBy { it.at }.forEach {
        if (beaten.isEmpty() || it.totalMs < beaten.last().totalMs) beaten += it
    }
    return beaten.reversed()
}

fun recordsToJson(records: List<SessionRecord>): String = JSONArray(records.map {
    JSONObject().put("at", it.at).put("totalMs", it.totalMs).put("key", it.key)
}).toString()

fun recordsFromJson(text: String?): List<SessionRecord> = text?.let {
    runCatching {
        val list = JSONArray(it)
        (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            SessionRecord(o.getLong("at"), o.getLong("totalMs"), o.getString("key"))
        }
    }.getOrNull()
}.orEmpty()
