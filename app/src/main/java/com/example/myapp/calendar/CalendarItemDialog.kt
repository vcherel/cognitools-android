package com.example.myapp.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.myapp.AppDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fullDateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val birthDateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
private val dayMonthFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

private val KIND_LABELS = listOf(
    CalendarKind.TASK to "Tâche",
    CalendarKind.BIRTHDAY to "Anniversaire",
    CalendarKind.EVENT to "Événement"
)

private enum class Picking { DATE, START, END }

/** Adds or edits one item; the kind can only be changed while adding. */
@OptIn(ExperimentalLayoutApi::class)
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
    var time by remember { mutableStateOf(initial.localTime()) }
    var endTime by remember { mutableStateOf(initial.localEndTime()) }
    var place by remember { mutableStateOf(initial.place.orEmpty()) }
    var yearKnown by remember { mutableStateOf(initial.yearKnown) }
    var reminderMinutes by remember { mutableStateOf(initial.reminderMinutes) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isNew) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KIND_LABELS.forEach { (option, label) ->
                        FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(label) })
                    }
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(if (kind == CalendarKind.BIRTHDAY) "Prénom" else "Titre") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )

            val dateText = when {
                kind != CalendarKind.BIRTHDAY -> fullDateFormat.format(date)
                yearKnown -> birthDateFormat.format(date)
                else -> dayMonthFormat.format(date)
            }
            ValueRow(if (kind == CalendarKind.BIRTHDAY) "Né le" else "Date", dateText) { picking = Picking.DATE }

            if (kind == CalendarKind.BIRTHDAY) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { yearKnown = !yearKnown }) {
                    Checkbox(checked = yearKnown, onCheckedChange = { yearKnown = it })
                    Text("Je connais l'année")
                }
                Text(
                    "Rappels une semaine avant, la veille à 23:59 et le jour même à 9:00.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            } else {
                ValueRow("Heure", time?.let(timeFormat::format) ?: "Aucune") { picking = Picking.START }
                if (kind == CalendarKind.EVENT && time != null) {
                    ValueRow("Fin", endTime?.let(timeFormat::format) ?: "Aucune") { picking = Picking.END }
                }
            }

            if (kind == CalendarKind.TASK) {
                Text(
                    if (time == null) "Rappel à 9:00 le jour même." else "Rappel à l'heure prévue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            }

            if (kind == CalendarKind.EVENT) {
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    label = { Text("Lieu") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Rappel", style = MaterialTheme.typography.labelLarge)
                val options = if (time == null) {
                    listOf(null to "Aucun", 0 to "Le jour même à 9:00", 1440 to "La veille à 9:00")
                } else {
                    listOf(null to "Aucun", 0 to "À l'heure", 60 to "1 h avant", 1440 to "La veille")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (minutes, label) ->
                        FilterChip(
                            selected = reminderMinutes == minutes,
                            onClick = { reminderMinutes = minutes },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Annuler") }
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        val isEvent = kind == CalendarKind.EVENT
                        val isBirthday = kind == CalendarKind.BIRTHDAY
                        val start = if (isBirthday) null else time
                        onSave(
                            initial.copy(
                                kind = kind,
                                title = title.trim(),
                                date = (if (isBirthday && !yearKnown) date.withYear(UNKNOWN_BIRTH_YEAR) else date).toString(),
                                time = start?.let(timeFormat::format),
                                endTime = if (isEvent && start != null) endTime?.let(timeFormat::format) else null,
                                place = if (isEvent) place.trim().ifEmpty { null } else null,
                                yearKnown = !isBirthday || yearKnown,
                                // "1 h avant" means nothing without a time, it falls back to the morning one.
                                reminderMinutes = if (!isEvent) null
                                else if (start == null && reminderMinutes == 60) 0 else reminderMinutes
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
            onConfirm = {
                date = it
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

@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
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
