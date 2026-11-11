package com.example.myapp.workout

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.LocalToolAccent
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.SuppressIdleReset
import com.example.myapp.ToolAccent
import com.example.myapp.ToolAccents
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

private val BossRed = Color(0xFFC94444)
private val BossAccent = ToolAccent(BossRed, Icons.Default.LocalFireDepartment)

/** The countdown before a timed exercise starts, to get into position. */
private const val LEAD_IN_MS = 3000L

/** What a set is measured against: the same round last session, and the best set ever (this session's included). */
private data class SetContext(val last: Int?, val best: Int?)

@Composable
fun WorkoutSessionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val program by remember(context) { WorkoutStore.program(context) }.collectAsState(initial = null)
    val xp by remember(context) { WorkoutStore.xp(context) }.collectAsState(initial = null)
    val records by remember(context) { WorkoutStore.records(context) }.collectAsState(initial = null)
    var progress by remember { mutableStateOf<SessionProgress?>(null) }
    var logged by remember { mutableStateOf(listOf<SessionProgress>()) }
    var burst by remember { mutableStateOf<Burst?>(null) }
    val cues = remember(context) { Cues(context) }
    DisposableEffect(cues) { onDispose { cues.release() } }
    // A phone locked between exercises would otherwise come back on the menu.
    SuppressIdleReset()

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Read through a lambda by whatever draws it, so the tick redraws the clock and the rings, not the whole screen.
    val clock = produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(100)
        }
    }
    val now = { clock.value }

    val shownProgram = program ?: return
    val totalXp = xp ?: return
    val allRecords = records ?: return
    val steps = remember(shownProgram) { sessionSteps(shownProgram) }
    if (steps.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    LaunchedEffect(steps) {
        val saved = WorkoutStore.loadSession(context, shownProgram)
        progress = saved?.takeIf { it.step < steps.size }
            ?: SessionProgress(System.currentTimeMillis(), shownProgram.recordKey()).also {
                WorkoutStore.saveSession(context, it)
            }
    }
    val current = progress ?: return

    fun update(next: SessionProgress) {
        progress = next
        scope.launch { WorkoutStore.saveSession(context, next) }
    }

    fun setContext(step: SessionStep.Work): SetContext {
        val size = shownProgram.exercises.size
        val index = step.slot % size
        val earlier = (0 until step.round).mapNotNull { current.sets.getOrNull(it * size + index) }
        val best = (listOfNotNull(bestSet(allRecords, step.exercise)) + earlier).maxOrNull()
        return SetContext(lastSet(allRecords, step.exercise, step.round), best)
    }

    fun advance(value: Int = 0) {
        val before = current
        val step = steps[before.step]
        if (step !is SessionStep.Work) {
            update(before.advance(steps, System.currentTimeMillis()))
            return
        }
        val set = setContext(step)
        val gained = setXp(xpFor(step.round, shownProgram), value, set.last)
        val next = before.advance(steps, System.currentTimeMillis(), value, gained.xp)
        val newBest = set.best != null && value > set.best
        val boss = isBossRound(step.round, shownProgram)
        when {
            gained.critical -> cues.critical()
            newBest -> cues.best()
            next.roundEnds.size > before.roundEnds.size -> cues.round()
            boss -> cues.strike()
            else -> cues.set()
        }
        val xpLine = "+${gained.xp} XP"
        burst = Burst(
            System.nanoTime(),
            when {
                newBest && gained.critical -> "New best\nCritical $xpLine"
                newBest -> "New best\n$xpLine"
                gained.critical -> "Critical\n$xpLine"
                else -> xpLine
            },
            gold = newBest || gained.critical
        )
        logged = logged + before
        update(next)
    }

    fun undoSet() {
        val before = logged.lastOrNull() ?: return
        cues.tap()
        logged = logged.dropLast(1)
        update(before.copy(stepStartedAt = System.currentTimeMillis(), timerStartedAt = null))
    }

    fun quit() {
        scope.launch {
            WorkoutStore.clearSession(context)
            onBack()
            AppSnackbar.show("Session quit", "Undo") { WorkoutStore.saveSession(context, current) }
        }
    }

    fun giveUp() {
        val now = System.currentTimeMillis()
        val xp = current.loggedXp()
        val record = SessionRecord(now, now - current.startedAt, partialKey(current.programKey), current.exerciseSets(shownProgram))
        scope.launch {
            WorkoutStore.finish(context, xp, record, gaveUp = true)
            onBack()
            AppSnackbar.show("Session saved, +$xp XP")
        }
    }

    fun restart() {
        val before = current
        val beforeLogged = logged
        logged = emptyList()
        update(SessionProgress(System.currentTimeMillis(), shownProgram.recordKey()))
        AppSnackbar.show("Session restarted", "Undo") {
            logged = beforeLogged
            update(before)
        }
    }

    val finishedAt = current.finishedAt
    if (finishedAt != null) {
        val best = recordProgression(allRecords, current.programKey).firstOrNull()?.totalMs
        val total = finishedAt - current.startedAt
        VictoryContent(
            current, shownProgram, totalXp, cues,
            newRecord = best != null && total < best,
            onBack = onBack,
            onUndo = if (logged.isNotEmpty()) ::undoSet else null
        ) {
            scope.launch {
                WorkoutStore.finish(
                    context,
                    current.loggedXp(),
                    SessionRecord(finishedAt, total, current.programKey, current.exerciseSets(shownProgram))
                )
                onBack()
            }
        }
        return
    }

    val step = steps[current.step]
    val boss = isBossRound(step.round, shownProgram)
    val accent = if (boss) BossAccent else LocalToolAccent.current
    CompositionLocalProvider(LocalToolAccent provides accent) {
        Box(Modifier.fillMaxSize()) {
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
                    SessionClock(current.startedAt, now)
                    if (logged.isNotEmpty()) {
                        IconButton(onClick = ::undoSet) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last set") }
                    }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (current.sets.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Give up and rest") },
                                    leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                                    onClick = { menuOpen = false; giveUp() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Restart session") },
                                leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                                onClick = { menuOpen = false; restart() }
                            )
                            DropdownMenuItem(
                                text = { Text("Quit session") },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                onClick = { menuOpen = false; quit() }
                            )
                        }
                    }
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
                            cues.tap()
                            update(current.copy(extraRest = current.extraRest + 15))
                        }, onDone = { advance() })
                        is SessionStep.Work -> {
                            if (boss) BossCard(shownProgram, step.slot, levelOf(totalXp).level)
                            WorkContent(
                                step.exercise, setContext(step), current, now, cues,
                                onStart = { update(current.copy(timerStartedAt = System.currentTimeMillis())) },
                                onReset = { update(current.copy(timerStartedAt = null)) },
                                onDone = ::advance
                            )
                        }
                    }
                }
            }
            burst?.let { key(it.id) { BurstOverlay(it) } }
        }
    }
}

@Composable
private fun SessionClock(startedAt: Long, now: () -> Long) {
    Text(formatClock(now() - startedAt), fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

internal fun formatClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** A set's value as text: the count, or the hold time for a timed exercise. */
internal fun formatSet(value: Int, type: ExerciseType) =
    if (type == ExerciseType.Timed) formatClock(value.toLong()) else "$value"

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
    WorkoutCard {
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

/**
 * The exercise card: its name, target and drawing, last time and best. A reps exercise is logged
 * by tapping its count; a timed one starts, counts up past its target and is logged by Stop.
 */
@Composable
private fun ColumnScope.WorkContent(
    exercise: Exercise,
    set: SetContext,
    progress: SessionProgress,
    now: () -> Long,
    cues: Cues,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onDone: (Int) -> Unit
) {
    val accent = LocalToolAccent.current!!.color
    val timed = exercise.type == ExerciseType.Timed
    val started = progress.timerStartedAt
    var padOpen by remember { mutableStateOf(false) }

    WorkoutCard(Modifier.weight(1f)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Text(
                exercise.name,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                exercise.target,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (timed && started != null) {
                HoldRing(exercise, set.best, started, now, cues)
            } else {
                ExerciseFigure(exercise.name, accent, Modifier.fillMaxWidth().weight(1f, fill = false).height(170.dp))
                SetLine(exercise.type, set)
            }
        }
    }

    when {
        timed && started == null -> MyButton(text = "Start") {
            cues.tap()
            onStart()
        }
        timed -> MyButton(text = "Stop", highlighted = true) {
            val held = now() - started!! - LEAD_IN_MS
            if (held <= 0) onReset() else onDone(held.toInt())
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repSuggestions(set.last, exercise.target).forEach { count ->
                MyButton(
                    text = "$count",
                    modifier = Modifier.weight(1f),
                    fontSize = 28.sp,
                    highlighted = count == set.last
                ) { onDone(count) }
            }
            MyButton(text = "Other", modifier = Modifier.weight(1f), icon = Icons.Default.Dialpad, secondary = true) {
                cues.tap()
                padOpen = true
            }
        }
    }

    if (padOpen) {
        NumberPadDialog(
            cues = cues,
            onDismiss = { padOpen = false },
            onConfirm = {
                padOpen = false
                onDone(it)
            }
        )
    }
}

/** "Last 12    Best 14", whichever exist. */
@Composable
private fun SetLine(type: ExerciseType, set: SetContext) {
    if (set.last == null && set.best == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        set.last?.let { LabeledValue("Last", formatSet(it, type), MaterialTheme.colorScheme.onSurface) }
        set.best?.let { LabeledValue("Best", formatSet(it, type), Gold) }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * The hold: a get-ready count, then the clock counting up while the ring fills toward the target.
 * Reaching the target beeps, passing the best turns the ring gold.
 */
@Composable
private fun HoldRing(exercise: Exercise, best: Int?, started: Long, now: () -> Long, cues: Cues) {
    val target = exercise.seconds.coerceAtLeast(1) * 1000L
    val sinceStart = now() - started
    val leadIn = sinceStart < LEAD_IN_MS
    val held = (sinceStart - LEAD_IN_MS).coerceAtLeast(0)
    val leadSecond = ceil((LEAD_IN_MS - sinceStart) / 1000.0).toInt()
    val reached = held >= target
    val beaten = best != null && held > best
    LaunchedEffect(started, leadIn, leadSecond) { if (leadIn) cues.tick() else if (sinceStart < LEAD_IN_MS + 500) cues.go() }
    LaunchedEffect(started, reached) { if (reached) cues.end() }
    LaunchedEffect(started, beaten) { if (beaten) cues.best() }
    val color = if (beaten) Gold else LocalToolAccent.current!!.color
    Ring(
        fraction = if (leadIn) 0f else (held / target.toFloat()).coerceAtMost(1f),
        color = color,
        label = if (leadIn) "$leadSecond" else formatClock(held),
        sub = when {
            leadIn -> "Get ready"
            beaten -> "New best"
            best != null -> "Best ${formatClock(best.toLong())}"
            else -> "Goal ${formatClock(target)}"
        }
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

/** Any other count, typed on big digit keys. */
@Composable
private fun NumberPadDialog(cues: Cues, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var digits by remember { mutableStateOf("") }
    val value = digits.toIntOrNull() ?: 0
    AppDialog(onDismiss = onDismiss) {
        Text(
            digits.ifEmpty { "0" },
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("del", "0", "ok"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { label ->
                        val modifier = Modifier.weight(1f)
                        when (label) {
                            "del" -> MyButton(text = "Delete", modifier = modifier, height = 64.dp, secondary = true,
                                icon = Icons.AutoMirrored.Filled.Backspace) {
                                cues.tap()
                                digits = digits.dropLast(1)
                            }
                            "ok" -> MyButton(text = "Ok", modifier = modifier, height = 64.dp, highlighted = true,
                                enabled = value > 0, icon = Icons.Default.Check) { onConfirm(value) }
                            else -> MyButton(text = label, modifier = modifier, height = 64.dp, fontSize = 26.sp) {
                                cues.tap()
                                if (digits.length < 3) digits = (digits + label).trimStart('0')
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.RestContent(
    step: SessionStep.Rest,
    progress: SessionProgress,
    now: () -> Long,
    cues: Cues,
    onExtend: () -> Unit,
    onDone: () -> Unit
) {
    val total = (step.seconds + progress.extraRest) * 1000L
    val left = (total - (now() - progress.stepStartedAt)).coerceAtLeast(0)
    val secondsLeft = ceil(left / 1000.0).toInt()
    LaunchedEffect(progress.step, secondsLeft) {
        when (secondsLeft) {
            0 -> { cues.end(); onDone() }
            1, 2, 3 -> cues.tick()
        }
    }
    WorkoutCard(Modifier.weight(1f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Ring(left / total.toFloat(), MaterialTheme.colorScheme.onSurfaceVariant, formatClock(left + 999), null)
        }
    }
    WorkoutCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Next", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(step.next.name, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                Text(step.next.target, style = MaterialTheme.typography.bodyLarge)
            }
            ExerciseFigure(step.next.name, LocalToolAccent.current!!.color, Modifier.size(96.dp, 70.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MyButton(text = "+15 s", modifier = Modifier.weight(1f), secondary = true, onClick = onExtend)
        MyButton(text = "Skip", modifier = Modifier.weight(1f)) {
            cues.tap()
            onDone()
        }
    }
}

/** The end of the session: its time (flagged when it beats the best), each round's, the XP filling the level bar, and Finish. */
@Composable
private fun VictoryContent(
    progress: SessionProgress,
    program: WorkoutProgram,
    totalXp: Int,
    cues: Cues,
    newRecord: Boolean,
    onBack: () -> Unit,
    onUndo: (() -> Unit)?,
    onFinish: () -> Unit
) {
    val gain = progress.loggedXp()
    val after = levelOf(totalXp + gain)
    val before = levelOf(totalXp)
    val levelUp = after.level > before.level
    val bar = remember { Animatable(if (levelUp) 0f else before.into / before.needed.toFloat()) }
    LaunchedEffect(Unit) {
        cues.victory()
        delay(400)
        bar.animateTo(after.into / after.needed.toFloat(), tween(1400))
        if (levelUp) cues.best()
    }
    val accent = LocalToolAccent.current!!.color
    val rounds = progress.roundDurations()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title = "Workout", onBack = onBack) {
                onUndo?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last set") } }
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Spacer(Modifier.weight(1f))
                WorkoutCard {
                    Text(
                        if (program.rounds > 1) "Boss defeated" else "Session done",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(formatClock(progress.finishedAt!! - progress.startedAt), fontSize = 60.sp, fontWeight = FontWeight.Bold)
                    if (newRecord) {
                        Text("New record", style = MaterialTheme.typography.titleMedium, color = accent)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rounds.forEachIndexed { i, ms ->
                        val boss = isBossRound(i, program)
                        WorkoutCard(Modifier.weight(1f)) {
                            Text(
                                if (boss) "Boss" else "Round ${i + 1}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (boss) BossRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(formatClock(ms), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                WorkoutCard {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (levelUp) "Level up: ${after.level}" else "Level ${after.level}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text("+$gain XP", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = accent)
                    }
                    LevelBar(bar.value, accent, 14.dp, Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Text(
                        "${after.into} / ${after.needed} XP",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.weight(1f))
                MyButton(text = "Finish", highlighted = true, onClick = onFinish)
            }
        }
        Confetti()
    }
}

