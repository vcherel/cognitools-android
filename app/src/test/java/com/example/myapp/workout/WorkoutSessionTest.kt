package com.example.myapp.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSessionTest {
    private val program = WorkoutProgram.DEFAULT
    private val steps = sessionSteps(program)

    @Test
    fun `three rounds of six exercises with a rest between each`() {
        val work = steps.filterIsInstance<SessionStep.Work>()
        val rests = steps.filterIsInstance<SessionStep.Rest>()
        assertEquals(18, work.size)
        assertEquals(17, rests.size)
        assertEquals((0 until 18).toList(), work.map { it.slot })
        assertTrue(steps.first() is SessionStep.Work)
        assertTrue(steps.last() is SessionStep.Work)
    }

    @Test
    fun `the rest between rounds is the long one`() {
        val rests = steps.filterIsInstance<SessionStep.Rest>()
        assertEquals(listOf(6, 12), rests.filter { it.seconds == 120 }.map { it.nextSlot })
        assertEquals(15, rests.count { it.seconds == 45 })
    }

    @Test
    fun `only the last round is the boss`() {
        assertEquals(listOf(false, false, true), (0..2).map { isBossRound(it, program) })
        assertEquals(false, isBossRound(0, program.copy(rounds = 1)))
    }

    @Test
    fun `a session is worth 120 XP with the boss doubled`() {
        assertEquals(120, sessionXp(program))
    }

    @Test
    fun `levels take 100 more XP each`() {
        assertEquals(Level(1, 0, 200), levelOf(0))
        assertEquals(Level(2, 0, 300), levelOf(200))
        assertEquals(Level(4, 360, 500), levelOf(200 + 300 + 400 + 360))
    }

    @Test
    fun `pull ups clear in four parts and sides in two`() {
        var progress = SessionProgress(startedAt = 0)
        repeat(3) { progress = progress.advance(steps, it + 1L) }
        assertEquals(0, progress.step)
        assertEquals(3, progress.part)
        progress = progress.advance(steps, 10)
        assertEquals(1, progress.step)
        assertEquals(0, progress.part)
        assertEquals(10, progress.stepStartedAt)
    }

    @Test
    fun `round ends are logged and the last part finishes the session`() {
        var progress = SessionProgress(startedAt = 0)
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
        val progress = SessionProgress(startedAt = 5, step = 3, part = 1, stepStartedAt = 9, extraRest = 15, roundEnds = listOf(40, 80))
        assertEquals(progress, sessionFromJson(progress.toJson()))
        assertEquals(progress.copy(finishedAt = 99), sessionFromJson(progress.copy(finishedAt = 99).toJson()))
    }

    @Test
    fun `a session with no rest has only work steps`() {
        val noRest = sessionSteps(program.copy(restSeconds = 0, roundRestSeconds = 0))
        assertEquals(18, noRest.size)
        assertNull(noRest.firstOrNull { it is SessionStep.Rest })
    }
}
