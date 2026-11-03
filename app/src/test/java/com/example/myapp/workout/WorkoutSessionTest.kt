package com.example.myapp.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSessionTest {
    private val program = WorkoutProgram.DEFAULT
    private val steps = sessionSteps(program)

    @Test
    fun `three rounds of eight exercises with a rest between each`() {
        val work = steps.filterIsInstance<SessionStep.Work>()
        val rests = steps.filterIsInstance<SessionStep.Rest>()
        assertEquals(24, work.size)
        assertEquals(23, rests.size)
        assertEquals((0 until 24).toList(), work.map { it.slot })
        assertTrue(steps.first() is SessionStep.Work)
        assertTrue(steps.last() is SessionStep.Work)
    }

    @Test
    fun `the rest between rounds is the long one`() {
        val rests = steps.filterIsInstance<SessionStep.Rest>()
        assertEquals(listOf(8, 16), rests.filter { it.seconds == 120 }.map { it.nextSlot })
        assertEquals(21, rests.count { it.seconds == 45 })
    }

    @Test
    fun `only the last round is the boss`() {
        assertEquals(listOf(false, false, true), (0..2).map { isBossRound(it, program) })
        assertEquals(false, isBossRound(0, program.copy(rounds = 1)))
    }

    @Test
    fun `a session is worth 160 XP with the boss doubled`() {
        assertEquals(160, sessionXp(program))
    }

    @Test
    fun `levels take 100 more XP each`() {
        assertEquals(Level(1, 0, 200), levelOf(0))
        assertEquals(Level(2, 0, 300), levelOf(200))
        assertEquals(Level(4, 360, 500), levelOf(200 + 300 + 400 + 360))
    }

    @Test
    fun `every card clears in one tap and the next one waits on its timer`() {
        var progress = SessionProgress(startedAt = 0, programKey = program.recordKey(), timerStartedAt = 4)
        progress = progress.advance(steps, 10)
        assertEquals(1, progress.step)
        assertEquals(10, progress.stepStartedAt)
        assertNull(progress.timerStartedAt)
    }

    @Test
    fun `records keep only the sessions that beat the best of the same program`() {
        val key = program.recordKey()
        val other = program.copy(rounds = 2).recordKey()
        val records = listOf(
            SessionRecord(1, 900, key),
            SessionRecord(2, 950, key),
            SessionRecord(3, 800, other),
            SessionRecord(4, 850, key)
        )
        assertEquals(listOf(4L, 1L), recordProgression(records, key).map { it.at })
        assertEquals(records, recordsFromJson(recordsToJson(records)))
        assertEquals(emptyList<SessionRecord>(), recordsFromJson("{broken"))
    }

    @Test
    fun `an exercise type no longer known reads as reps`() {
        val json = program.toJson().replace("\"Timed\"", "\"Negatives\"")
        assertEquals(ExerciseType.Reps, programFromJson(json).exercises[4].type)
    }

    @Test
    fun `round ends are logged and the last part finishes the session`() {
        var progress = SessionProgress(startedAt = 0, programKey = program.recordKey())
        var now = 0L
        while (progress.finishedAt == null) progress = progress.advance(steps, ++now)
        assertEquals(3, progress.roundEnds.size)
        assertEquals(now, progress.finishedAt)
        assertEquals(now, progress.roundEnds.last())
        assertEquals(now, progress.roundDurations().sum())
    }

    @Test
    fun `a broken program falls back to the default`() {
        assertEquals(program, programFromJson("{not json"))
        assertEquals(program, programFromJson(null))
        assertEquals(program.copy(rounds = 2), programFromJson(program.copy(rounds = 2).toJson()))
    }

    @Test
    fun `a session in progress survives its JSON`() {
        val progress = SessionProgress(startedAt = 5, programKey = program.recordKey(), step = 3, stepStartedAt = 9, timerStartedAt = 12, extraRest = 15, roundEnds = listOf(40, 80))
        assertEquals(progress, sessionFromJson(progress.toJson()))
        assertEquals(progress.copy(finishedAt = 99), sessionFromJson(progress.copy(finishedAt = 99).toJson()))
    }

    @Test
    fun `each set is logged in slot order and grouped by exercise`() {
        var progress = SessionProgress(startedAt = 0, programKey = program.recordKey())
        var now = 0L
        while (progress.finishedAt == null) {
            val step = steps[progress.step]
            progress = progress.advance(steps, ++now, if (step is SessionStep.Work) step.slot + 1 else 0)
        }
        assertEquals((1..24).toList(), progress.sets)
        val grouped = progress.exerciseSets(program)
        assertEquals(ExerciseSets("Pull ups", ExerciseType.Reps, listOf(1, 9, 17)), grouped[0])
        assertEquals(ExerciseType.Timed, grouped[4].type)
        assertEquals(progress, sessionFromJson(progress.toJson()))
    }

    @Test
    fun `suggestions and bests come from the sets of earlier sessions`() {
        val pushUps = program.exercises[1]
        val records = listOf(
            SessionRecord(1, 900, "a", listOf(ExerciseSets("Push ups", ExerciseType.Reps, listOf(10, 9, 8)))),
            SessionRecord(2, 900, "b", listOf(ExerciseSets("Push ups", ExerciseType.Reps, listOf(12, 11))))
        )
        assertEquals(11, lastSet(records, pushUps, 1))
        assertEquals(11, lastSet(records, pushUps, 2))
        assertEquals(12, bestSet(records, pushUps))
        assertNull(lastSet(records, program.exercises[2], 0))
        assertEquals(listOf(10, 11, 12, 13), repSuggestions(11, "Max minus 2"))
        assertEquals(listOf(14, 15, 16, 17), repSuggestions(null, "15 to 20"))
        assertEquals(listOf(1, 2, 3), repSuggestions(1, ""))
        assertEquals(records, recordsFromJson(recordsToJson(records)))
    }

    @Test
    fun `a session with no rest has only work steps`() {
        val noRest = sessionSteps(program.copy(restSeconds = 0, roundRestSeconds = 0))
        assertEquals(24, noRest.size)
        assertNull(noRest.firstOrNull { it is SessionStep.Rest })
    }
}
