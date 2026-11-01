package com.example.myapp.workout

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppSnackbar
import com.example.myapp.LocalToolAccent
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ToolAccent
import com.example.myapp.ToolAccents
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.random.Random

private val BossRed = Color(0xFFC94444)
private val BossAccent = ToolAccent(BossRed, Icons.Default.LocalFireDepartment)

/** The countdown before a timed exercise starts, to get into position. */
private const val LEAD_IN_MS = 3000L

/** Vibrations and beeps; the beeps go through the notification stream so music keeps playing. */
private class Cues(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    fun tap() = vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    fun tick() = vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

    fun end() {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
    }

    fun round() {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 60, 60, 60, 300), -1))
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 500)
    }

    fun release() = tone?.release()
}

@Composable
fun WorkoutSessionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val program by remember(context) { WorkoutStore.program(context) }.collectAsState(initial = null)
    val xp by remember(context) { WorkoutStore.xp(context) }.collectAsState(initial = null)
    var progress by remember { mutableStateOf<SessionProgress?>(null) }
    val cues = remember(context) { Cues(context) }
    DisposableEffect(cues) { onDispose { cues.release() } }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(100)
        }
    }

    val shownProgram = program ?: return
    val totalXp = xp ?: return
    val steps = remember(shownProgram) { sessionSteps(shownProgram) }

    LaunchedEffect(steps) {
        val saved = WorkoutStore.loadSession(context)
        progress = saved?.takeIf { it.step < steps.size } ?: SessionProgress(System.currentTimeMillis()).also {
            WorkoutStore.saveSession(context, it)
        }
    }
    val current = progress ?: return
    if (steps.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    fun update(next: SessionProgress) {
        progress = next
        scope.launch { WorkoutStore.saveSession(context, next) }
    }

    fun advance() {
        val before = current
        val next = before.advance(steps, System.currentTimeMillis())
        if (next.roundEnds.size > before.roundEnds.size) cues.round() else cues.tap()
        update(next)
    }

    fun quit() {
        scope.launch {
            WorkoutStore.clearSession(context)
            onBack()
            AppSnackbar.show("Session ended", "Undo") { WorkoutStore.saveSession(context, current) }
        }
    }

    if (current.finishedAt != null) {
        VictoryContent(current, shownProgram, totalXp, onBack = onBack) {
            scope.launch {
                WorkoutStore.finish(context, sessionXp(shownProgram))
                onBack()
            }
        }
        return
    }

    val step = steps[current.step]
    val boss = isBossRound(step.round, shownProgram)
    val accent = if (boss) BossAccent else LocalToolAccent.current
    CompositionLocalProvider(LocalToolAccent provides accent) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = when {
                    step is SessionStep.Rest -> "Rest"
                    boss -> "Boss"
                    else -> "Round ${step.round + 1}"
                },
                onBack = onBack,
                titleWeight = true
            ) {
                Text(
                    formatClock(now - current.startedAt),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = ::quit) { Icon(Icons.Default.Close, contentDescription = "End session") }
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val slot = when (step) {
                    is SessionStep.Work -> step.slot
                    is SessionStep.Rest -> step.nextSlot
                }
                ProgressPath(shownProgram, slot)
                when (step) {
                    is SessionStep.Rest -> RestContent(step, current, now, cues, onExtend = {
                        update(current.copy(extraRest = current.extraRest + 15))
                    }, onDone = ::advance)
                    is SessionStep.Work -> {
                        if (boss) BossCard(shownProgram, step.slot, levelOf(totalXp).level)
                        WorkContent(step.exercise, current, now, boss, cues, onDone = ::advance)
                    }
                }
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** One dot per exercise of the session, grouped by round, the boss round in red. */
@Composable
private fun ProgressPath(program: WorkoutProgram, slot: Int) {
    val size = program.exercises.size
    val workoutGreen = ToolAccents.Workout.color
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (round in 0 until program.rounds) {
            val boss = isBossRound(round, program)
            val color = if (boss) BossRed else workoutGreen
            Row(
                Modifier.weight(1f).height(18.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until size) {
                    val s = round * size + i
                    val shape = RoundedCornerShape(5.dp)
                    val modifier = Modifier.weight(1f)
                    when {
                        s < slot -> Box(modifier.height(10.dp).background(color, shape))
                        s == slot -> Box(
                            modifier.height(18.dp)
                                .background(MaterialTheme.colorScheme.background, shape)
                                .border(3.dp, color, shape)
                        )
                        else -> Box(
                            modifier.height(10.dp)
                                .background(if (boss) color.copy(alpha = 0.22f) else empty, shape)
                        )
                    }
                }
            }
        }
    }
}

/** The boss's health: one hit per exercise of the round, the last one lost shown as a pale chunk. */
@Composable
private fun BossCard(program: WorkoutProgram, slot: Int, level: Int) {
    val size = program.exercises.size
    val cleared = slot - (program.rounds - 1) * size
    val health = size - cleared
    val fraction by animateFloatAsState(health / size.toFloat(), tween(500), label = "health")
    val pop = remember { Animatable(0f) }
    LaunchedEffect(cleared) {
        if (cleared > 0) {
            pop.snapTo(1f)
            pop.animateTo(0f, tween(1200))
        }
    }
    Card {
        Box(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(bossName(level), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("$health / $size", style = MaterialTheme.typography.titleMedium, color = BossRed)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(20.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
                ) {
                    Box(
                        Modifier.fillMaxWidth((health + 1).coerceAtMost(size) / size.toFloat()).height(20.dp)
                            .alpha(if (cleared > 0) 1f else 0f)
                            .background(BossRed.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    )
                    Box(Modifier.fillMaxWidth(fraction).height(20.dp).background(BossRed, RoundedCornerShape(10.dp)))
                }
            }
            if (pop.value > 0f) {
                Text(
                    "-1",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFF8F00),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 56.dp)
                        .offset(y = ((pop.value - 1f) * 16).dp)
                        .alpha(pop.value)
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.WorkContent(
    exercise: Exercise,
    progress: SessionProgress,
    now: Long,
    boss: Boolean,
    cues: Cues,
    onDone: () -> Unit
) {
    val accent = LocalToolAccent.current?.color ?: Color.Gray
    // The descent of a negative in progress, started by its button; not saved, a killed one restarts.
    var descentStart by remember(progress.step, progress.part) { mutableStateOf<Long?>(null) }

    Card(Modifier.weight(1f), content = {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            Text(exercise.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                exercise.target,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            when (exercise.type) {
                ExerciseType.Reps -> Unit
                ExerciseType.PerSide -> Text(
                    "Side ${progress.part + 1} of 2",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
                ExerciseType.Timed -> TimedRing(exercise, progress, now, cues)
                ExerciseType.Negatives -> NegativeSteps(exercise, progress.part, descentStart, now, accent, cues) {
                    descentStart = null
                    onDone()
                }
            }
        }
    })

    val negative = exercise.type == ExerciseType.Negatives && progress.part > 0
    when {
        negative && descentStart != null -> MyButton(text = "Lowering", enabled = false) {}
        negative -> MyButton(text = "Start negative") {
            cues.tap()
            descentStart = System.currentTimeMillis()
        }
        boss -> MyButton(text = "Strike", onClick = onDone)
        else -> MyButton(text = "Done", icon = Icons.Default.Check, onClick = onDone)
    }
}

/** The full reps as a check box, then one numbered box per negative, and the descent bar. */
@Composable
private fun NegativeSteps(
    exercise: Exercise,
    part: Int,
    descentStart: Long?,
    now: Long,
    accent: Color,
    cues: Cues,
    onDescended: () -> Unit
) {
    val descentMs = exercise.seconds.coerceAtLeast(1) * 1000L
    val elapsed = descentStart?.let { now - it } ?: 0L
    val second = (elapsed / 1000).toInt()
    LaunchedEffect(descentStart, second) {
        if (descentStart != null && second in 1 until exercise.seconds) cues.tick()
    }
    LaunchedEffect(descentStart, elapsed >= descentMs) {
        if (descentStart != null && elapsed >= descentMs) onDescended()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        for (i in 0..exercise.negatives) {
            val shape = RoundedCornerShape(16.dp)
            val base = Modifier.size(54.dp)
            val box = when {
                i < part -> base.background(accent, shape)
                i == part -> base.background(MaterialTheme.colorScheme.background, shape).border(3.dp, accent, shape)
                else -> base.background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f), shape)
            }
            Box(box, contentAlignment = Alignment.Center) {
                val color = when {
                    i < part -> Color.White
                    i == part -> accent
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                if (i == 0) Icon(Icons.Default.Check, contentDescription = null, tint = color)
                else Text("$i", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
    if (part > 0) {
        val fraction = (elapsed / descentMs.toFloat()).coerceIn(0f, 1f)
        Box(
            Modifier.padding(top = 8.dp).width(200.dp).height(12.dp)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp))
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(12.dp).background(accent, RoundedCornerShape(6.dp)))
        }
    }
}

/** A get-ready count, then the ring running down the exercise's seconds. */
@Composable
private fun TimedRing(exercise: Exercise, progress: SessionProgress, now: Long, cues: Cues) {
    val total = exercise.seconds.coerceAtLeast(1) * 1000L
    val sinceStep = now - progress.stepStartedAt
    val leadIn = sinceStep < LEAD_IN_MS
    val elapsed = (sinceStep - LEAD_IN_MS).coerceIn(0, total)
    val left = total - elapsed
    val leadSecond = ceil((LEAD_IN_MS - sinceStep) / 1000.0).toInt()
    LaunchedEffect(progress.stepStartedAt, leadIn, leadSecond) { if (leadIn) cues.tick() else if (sinceStep < LEAD_IN_MS + 500) cues.tap() }
    LaunchedEffect(progress.stepStartedAt, left == 0L) { if (left == 0L) cues.end() }
    Ring(
        fraction = if (leadIn) 1f else left / total.toFloat(),
        color = LocalToolAccent.current?.color ?: Color.Gray,
        label = if (leadIn) "$leadSecond" else formatClock(left + 999),
        sub = if (leadIn) "Get ready" else "of ${formatClock(total)}"
    )
}

@Composable
private fun Ring(fraction: Float, color: Color, label: String, sub: String?) {
    val track = MaterialTheme.colorScheme.background
    Box(Modifier.size(210.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            sub?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ColumnScope.RestContent(
    step: SessionStep.Rest,
    progress: SessionProgress,
    now: Long,
    cues: Cues,
    onExtend: () -> Unit,
    onDone: () -> Unit
) {
    val total = (step.seconds + progress.extraRest) * 1000L
    val left = (total - (now - progress.stepStartedAt)).coerceAtLeast(0)
    val secondsLeft = ceil(left / 1000.0).toInt()
    LaunchedEffect(progress.step, secondsLeft) {
        when (secondsLeft) {
            0 -> { cues.end(); onDone() }
            1, 2, 3 -> cues.tick()
        }
    }
    Card(Modifier.weight(1f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Ring(left / total.toFloat(), MaterialTheme.colorScheme.onSurfaceVariant, formatClock(left + 999), null)
        }
    }
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Next", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(step.next.name, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text(step.next.target, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MyButton(text = "+15 s", modifier = Modifier.weight(1f), secondary = true, onClick = onExtend)
        MyButton(text = "Skip", modifier = Modifier.weight(1f), onClick = onDone)
    }
}

/** The end of the session: its time, each round's, the XP filling the level bar, and Finish. */
@Composable
private fun VictoryContent(
    progress: SessionProgress,
    program: WorkoutProgram,
    totalXp: Int,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val gain = sessionXp(program)
    val after = levelOf(totalXp + gain)
    val before = levelOf(totalXp)
    val levelUp = after.level > before.level
    val bar = remember { Animatable(if (levelUp) 0f else before.into / before.needed.toFloat()) }
    LaunchedEffect(Unit) {
        delay(400)
        bar.animateTo(after.into / after.needed.toFloat(), tween(1400))
    }
    val accent = LocalToolAccent.current?.color ?: Color.Gray
    val rounds = progress.roundDurations()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title = "Workout", onBack = onBack)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Spacer(Modifier.weight(1f))
                Card {
                    Text(
                        if (program.rounds > 1) "Boss defeated" else "Session done",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(formatClock(progress.finishedAt!! - progress.startedAt), fontSize = 60.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rounds.forEachIndexed { i, ms ->
                        val boss = isBossRound(i, program)
                        Card(Modifier.weight(1f)) {
                            Text(
                                if (boss) "Boss" else "Round ${i + 1}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (boss) BossRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(formatClock(ms), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Card {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (levelUp) "Level up: ${after.level}" else "Level ${after.level}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text("+$gain XP", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = accent)
                    }
                    Box(
                        Modifier.padding(top = 10.dp, bottom = 6.dp).fillMaxWidth().height(14.dp)
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(7.dp))
                    ) {
                        Box(Modifier.fillMaxWidth(bar.value).height(14.dp).background(accent, RoundedCornerShape(7.dp)))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "${after.into} / ${after.needed} XP",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                MyButton(text = "Finish", highlighted = true, onClick = onFinish)
            }
        }
        Confetti()
    }
}

private class Piece(val x: Float, val delay: Float, val speed: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

private val CONFETTI_COLORS = listOf(
    Color(0xFFFFB300), Color(0xFF2E9E6A), Color(0xFFC94444), Color(0xFF3F74D6), Color(0xFFE58A2B)
)

/** A burst of falling confetti, once, when the victory screen opens. */
@Composable
private fun Confetti() {
    val pieces = remember {
        val random = Random(System.nanoTime())
        List(60) {
            Piece(
                x = random.nextFloat(),
                delay = random.nextFloat() * 0.3f,
                speed = 0.6f + random.nextFloat() * 0.6f,
                spin = random.nextFloat() * 720f - 360f,
                color = CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.size)],
                w = 6f + random.nextFloat() * 6f,
                h = 4f + random.nextFloat() * 8f
            )
        }
    }
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) { time.animateTo(1f, tween(3200, easing = LinearEasing)) }
    if (time.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        pieces.forEach { p ->
            val t = ((time.value - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) return@forEach
            val y = -20f + t * p.speed * size.height * 1.3f
            val x = p.x * size.width + kotlin.math.sin(t * 12f + p.spin) * 18f
            val w = p.w * density
            val h = p.h * density
            rotate(p.spin * t, Offset(x, y)) {
                drawRect(p.color.copy(alpha = 1f - t * 0.6f), Offset(x - w / 2, y - h / 2), Size(w, h))
            }
        }
    }
}
