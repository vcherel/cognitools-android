package com.example.myapp.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.myapp.AppDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val fullDateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val shortDateFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
private val shortDateYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH)
private val birthDateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
private val dayMonthFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

private val KIND_LABELS = listOf(
    CalendarKind.TASK to "Tâche",
    CalendarKind.BIRTHDAY to "Anniv.",
    CalendarKind.EVENT to "Événement"
)

private val REPEAT_OPTIONS = listOf(false to "Jamais", true to "Chaque semaine")

private enum class Picking { DATE, END_DATE, START, END }

private fun shortDate(day: LocalDate): String =
    (if (day.year == LocalDate.now().year) shortDateFormat else shortDateYearFormat).format(day)

/** Adds or edits one item; the kind can only be changed while adding. */
@Composable
fun CalendarItemDialog(
    initial: CalendarItem,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CalendarItem) -> Unit,
    onDelete: () -> Unit
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(initial.localDate()) }
    var endDate by remember { mutableStateOf(initial.endDate?.let(LocalDate::parse)) }
    var time by remember { mutableStateOf(initial.localTime()) }
    var endTime by remember { mutableStateOf(initial.localEndTime()) }
    var yearKnown by remember { mutableStateOf(initial.yearKnown) }
    var weekly by remember { mutableStateOf(initial.weekly) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val isEvent = kind == CalendarKind.EVENT
    val isBirthday = kind == CalendarKind.BIRTHDAY

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isNew) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KIND_LABELS.forEach { (option, label) ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(if (isBirthday) "Prénom" else "Titre") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )

            if (isBirthday) {
                RangeRow(
                    "Né le", if (yearKnown) birthDateFormat.format(date) else dayMonthFormat.format(date),
                    onFirst = { picking = Picking.DATE }
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { yearKnown = !yearKnown }) {
                    Checkbox(checked = yearKnown, onCheckedChange = { yearKnown = it })
                    Text("Je connais l'année")
                }
            } else {
                if (isEvent) {
                    RangeRow(
                        "Date", shortDate(date), { picking = Picking.DATE },
                        second = endDate?.let(::shortDate), onSecond = { picking = Picking.END_DATE }
                    )
                } else {
                    RangeRow("Date", fullDateFormat.format(date), onFirst = { picking = Picking.DATE })
                }
                RangeRow(
                    "Heure", time?.let(timeFormat::format) ?: "Aucune", { picking = Picking.START },
                    second = endTime?.let(timeFormat::format),
                    onSecond = if (isEvent && time != null) ({ picking = Picking.END }) else null
                )
            }

            if (kind == CalendarKind.TASK) {
                ChoiceRow("Répéter", REPEAT_OPTIONS, weekly) { weekly = it }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Annuler") }
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        val start = if (isBirthday) null else time
                        onSave(
                            initial.copy(
                                kind = kind,
                                title = title.trim(),
                                date = (if (isBirthday && !yearKnown) date.withYear(UNKNOWN_BIRTH_YEAR) else date).toString(),
                                endDate = if (isEvent) endDate?.takeIf { it.isAfter(date) }?.toString() else null,
                                time = start?.let(timeFormat::format),
                                endTime = if (isEvent && start != null) endTime?.let(timeFormat::format) else null,
                                yearKnown = !isBirthday || yearKnown,
                                weekly = kind == CalendarKind.TASK && weekly,
                                done = initial.done && !(kind == CalendarKind.TASK && weekly),
                                snoozedUntil = null
                            )
                        )
                    }
                ) { Text("Enregistrer") }
            }
        }
    }

    when (picking) {
        Picking.DATE -> DatePickDialog(
            initial = date,
            onDismiss = { picking = null },
            onConfirm = { picked ->
                // Moving the first day carries the last one along, so the event keeps its length.
                endDate = endDate?.plusDays(ChronoUnit.DAYS.between(date, picked))
                date = picked
                picking = null
            }
        )
        Picking.END_DATE -> DatePickDialog(
            initial = endDate ?: date.plusDays(1),
            onDismiss = { picking = null },
            onClear = {
                endDate = null
                picking = null
            },
            onConfirm = {
                endDate = it.takeIf { it.isAfter(date) }
                picking = null
            }
        )
        Picking.START, Picking.END -> {
            val isStart = picking == Picking.START
            TimePickDialog(
                title = if (isStart) "Heure" else "Fin",
                initial = (if (isStart) time else endTime) ?: time ?: LocalTime.of(12, 0),
                onDismiss = { picking = null },
                onClear = {
                    if (isStart) {
                        time = null
                        endTime = null
                    } else {
                        endTime = null
                    }
                    picking = null
                },
                onConfirm = {
                    if (isStart) time = it else endTime = it
                    picking = null
                }
            )
        }
        null -> Unit
    }
}

/**
 * A label and its value, each value tapped to change it. With [onSecond] the row holds a range,
 * "first → second", the second shown as "…" until it is set.
 */
@Composable
private fun RangeRow(
    label: String,
    first: String,
    onFirst: () -> Unit,
    second: String? = null,
    onSecond: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        ValueText(first, onClick = onFirst)
        if (onSecond != null) {
            Text("→", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ValueText(second ?: "…", muted = second == null, onClick = onSecond)
        }
    }
}

/** A label and the chosen option, the options in a menu opened by tapping the row. */
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { open = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            ValueText(options.firstOrNull { it.first == selected }?.second ?: options.first().second, onClick = { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        onSelect(value)
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
private fun ValueText(text: String, muted: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (muted) FontWeight.Normal else FontWeight.SemiBold,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    onClear: (() -> Unit)? = null
) {
    // A French locale starts the weeks on Monday whatever the phone's language.
    val pickerState = remember {
        DatePickerState(
            locale = Locale.FRANCE,
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let {
                        onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
                enabled = pickerState.selectedDateMillis != null
            ) { Text("Ok") }
        },
        dismissButton = {
            Row {
                onClear?.let { TextButton(onClick = it) { Text("Aucune") } }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        }
    ) {
        DatePicker(state = pickerState, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    val pickerState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AppDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        TimePicker(state = pickerState, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onClear) { Text("Aucune") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Annuler") }
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) { Text("Ok") }
        }
    }
}
