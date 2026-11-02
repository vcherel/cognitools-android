package com.example.myapp.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.ToolAccents
import com.example.myapp.plural
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.FRENCH)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

private const val HISTORY_SHOWN = 10

/** "lundi 27 oct." */
fun formatDay(date: LocalDateTime): String = dayFormat.format(date)

/** The line under the reminder, on screen and in the notification: the last session, or how long ago. */
fun lastSessionText(lastDone: LocalDateTime?, today: LocalDate): String? {
    val days = daysSinceSession(lastDone, today) ?: return null
    return if (days >= LONG_BREAK_DAYS) "$days days without a session" else "Last session: ${formatDay(lastDone!!)}"
}

@Composable
fun WorkoutScreen(
    onBack: () -> Unit,
    onStartSession: () -> Unit,
    onOpenProgram: () -> Unit,
    pickDayInitially: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by remember(context) { WorkoutReminder.state(context) }.collectAsState(initial = null)
    val program by remember(context) { WorkoutStore.program(context) }.collectAsState(initial = WorkoutProgram.DEFAULT)
    val xp by remember(context) { WorkoutStore.xp(context) }.collectAsState(initial = 0)
    val records by remember(context) { WorkoutStore.records(context) }.collectAsState(initial = emptyList())
    var resumable by remember { mutableStateOf(false) }
    LaunchedEffect(program) { resumable = WorkoutStore.loadSession(context, program) != null }
    var editingWeekend by remember { mutableStateOf<Boolean?>(null) }
    var pickingDay by rememberSaveable { mutableStateOf(pickDayInitially) }

    ScreenTopBar(title = "Workout", onBack = onBack)

    val current = state ?: return
    val today = LocalDate.now()
    val sessions = current.sessions.map { it.toLocalDateTime() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 120.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WorkoutCard(onClick ={ pickingDay = true }) {
            val remindAt = current.remindAt?.toLocalDateTime()
            Text(
                if (current.due) "It's time!" else "Next reminder",
                style = MaterialTheme.typography.titleMedium
            )
            if (!current.due && remindAt != null) {
                Text(
                    "${formatDay(remindAt).replaceFirstChar { it.uppercase() }} at ${timeFormat.format(remindAt)}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                lastSessionText(sessions.firstOrNull(), today) ?: "No sessions logged",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MyButton(
                text = if (resumable) "Resume session" else "Start session",
                modifier = Modifier.weight(1f),
                highlighted = true,
                enabled = program.exercises.isNotEmpty(),
                onClick = onStartSession
            )
            MyButton(text = "Session done", modifier = Modifier.width(90.dp), icon = Icons.Default.Check) {
                scope.launch { WorkoutReminder.markDoneWithUndo(context) }
            }
        }

        val level = levelOf(xp)
        WorkoutCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Level ${level.level}", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    "${level.into} / ${level.needed} XP",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LevelBar(
                level.into / level.needed.toFloat(),
                ToolAccents.Workout.color,
                12.dp,
                Modifier.padding(top = 8.dp)
            )
        }

        WorkoutCard(onClick =onOpenProgram) {
            Text("Program", style = MaterialTheme.typography.titleMedium)
            val count = program.exercises.size
            Text(
                "${program.rounds} round${plural(program.rounds)}, $count exercise${plural(count)}",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        if (current.due) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(text = "In 1 h", modifier = Modifier.weight(1f), fontSize = 20.sp, secondary = true) {
                    scope.launch { WorkoutReminder.snoozeLater(context) }
                }
                MyButton(text = "Tomorrow", modifier = Modifier.weight(1f), fontSize = 20.sp, secondary = true) {
                    scope.launch { WorkoutReminder.snoozeDay(context) }
                }
            }
        }

        WorkoutCard {
            Text("Reminders", style = MaterialTheme.typography.titleMedium)
            ValueRow("Weekdays", timeFormat.format(current.times.weekday)) { editingWeekend = false }
            ValueRow("Weekends", timeFormat.format(current.times.weekend)) { editingWeekend = true }
        }

        val bests = recordProgression(records, program.recordKey())
        if (bests.isNotEmpty()) {
            WorkoutCard {
                Text("Records", style = MaterialTheme.typography.titleMedium)
                bests.take(HISTORY_SHOWN).forEachIndexed { i, record ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatDay(record.at.toLocalDateTime()),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatClock(record.totalMs),
                            fontSize = if (i == 0) 22.sp else 17.sp,
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        WorkoutCard {
            Text("History", style = MaterialTheme.typography.titleMedium)
            val month = sessionsInMonth(sessions, today)
            val year = sessionsInYear(sessions, today)
            Text("This month: $month session${plural(month)}", style = MaterialTheme.typography.bodyLarge)
            Text("This year: $year session${plural(year)}", style = MaterialTheme.typography.bodyLarge)
            if (sessions.isNotEmpty()) Spacer(Modifier.height(4.dp))
            sessions.take(HISTORY_SHOWN).forEach {
                Text(formatDay(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    editingWeekend?.let { weekend ->
        TimePickerDialog(
            title = if (weekend) "Weekend reminder" else "Weekday reminder",
            initial = if (weekend) current.times.weekend else current.times.weekday,
            onDismiss = { editingWeekend = null },
            onConfirm = { time ->
                editingWeekend = null
                val times = if (weekend) current.times.copy(weekend = time) else current.times.copy(weekday = time)
                scope.launch { WorkoutReminder.setTimes(context, times) }
            }
        )
    }

    if (pickingDay) {
        ReminderDayDialog(
            times = current.times,
            onDismiss = { pickingDay = false },
            onConfirm = { date ->
                pickingDay = false
                scope.launch { WorkoutReminder.snoozeUntil(context, date) }
            }
        )
    }
}

/** The tool's rounded card, its content centered. */
@Composable
internal fun WorkoutCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        content()
    }
}

/** The [fraction] filled part of a rounded bar, on the screen's background color. */
@Composable
internal fun LevelBar(fraction: Float, color: Color, height: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.background, shape)) {
        Box(Modifier.fillMaxWidth(fraction).height(height).background(color, shape))
    }
}

/** A tappable setting line: its label, its value in bold at the end. */
@Composable
internal fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** The day the reminder comes back, at that day's usual time. Only days whose slot is still ahead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDayDialog(times: ReminderTimes, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val now = remember { LocalDateTime.now() }
    // A French locale starts the weeks on Monday whatever the phone's language.
    val pickerState = remember {
        DatePickerState(
            locale = Locale.FRANCE,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = canRemindOn(utcTimeMillis.toUtcDate(), now, times)
                override fun isSelectableYear(year: Int) = year >= now.year
            }
        )
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onConfirm(it.toUtcDate()) } },
                enabled = pickerState.selectedDateMillis != null
            ) { Text("Ok") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(
            state = pickerState,
            showModeToggle = false,
            title = { Text("Next reminder on", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }
        )
    }
}

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val pickerState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AppDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        TimePicker(state = pickerState, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) { Text("Ok") }
        }
    }
}
