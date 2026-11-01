package com.example.myapp.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AppDialog
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
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
    return if (days >= LONG_BREAK_DAYS) "$days jours sans séance" else "Dernière séance : ${formatDay(lastDone!!)}"
}

@Composable
fun WorkoutScreen(onBack: () -> Unit, pickDayInitially: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stateFlow = remember(context) { WorkoutReminder.state(context) }
    val state by stateFlow.collectAsState(initial = null)
    var editingWeekend by remember { mutableStateOf<Boolean?>(null) }
    var pickingDay by rememberSaveable { mutableStateOf(pickDayInitially) }

    ScreenTopBar(title = "Muscu", onBack = onBack)

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
        Card(onClick = { pickingDay = true }) {
            val remindAt = current.remindAt?.toLocalDateTime()
            Text(
                if (current.due) "C'est l'heure !" else "Prochain rappel",
                style = MaterialTheme.typography.titleMedium
            )
            if (!current.due && remindAt != null) {
                Text(
                    "${formatDay(remindAt).replaceFirstChar { it.uppercase() }} à ${timeFormat.format(remindAt)}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                lastSessionText(sessions.firstOrNull(), today) ?: "Aucune séance enregistrée",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        MyButton(text = "Séance faite", icon = Icons.Default.FitnessCenter) {
            scope.launch { WorkoutReminder.markDoneWithUndo(context) }
        }

        if (current.due) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyButton(text = "Dans 1 h", modifier = Modifier.weight(1f), fontSize = 20.sp) {
                    scope.launch { WorkoutReminder.snoozeLater(context) }
                }
                MyButton(text = "Demain", modifier = Modifier.weight(1f), fontSize = 20.sp) {
                    scope.launch { WorkoutReminder.snoozeDay(context) }
                }
            }
        }

        Card {
            Text("Rappels", style = MaterialTheme.typography.titleMedium)
            TimeRow("En semaine", current.times.weekday) { editingWeekend = false }
            TimeRow("Le week-end", current.times.weekend) { editingWeekend = true }
        }

        Card {
            Text("Historique", style = MaterialTheme.typography.titleMedium)
            val month = sessionsInMonth(sessions, today)
            val year = sessionsInYear(sessions, today)
            Text("Ce mois-ci : $month séance${plural(month)}", style = MaterialTheme.typography.bodyLarge)
            Text("Cette année : $year séance${plural(year)}", style = MaterialTheme.typography.bodyLarge)
            if (sessions.isNotEmpty()) Spacer(Modifier.padding(top = 4.dp))
            sessions.take(HISTORY_SHOWN).forEach {
                Text(formatDay(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    editingWeekend?.let { weekend ->
        TimePickerDialog(
            title = if (weekend) "Rappel du week-end" else "Rappel en semaine",
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

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
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

@Composable
private fun TimeRow(label: String, time: LocalTime, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(timeFormat.format(time), fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    ) {
        DatePicker(
            state = pickerState,
            showModeToggle = false,
            title = { Text("Prochain rappel le", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }
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
            TextButton(onClick = onDismiss) { Text("Annuler") }
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) { Text("Ok") }
        }
    }
}
