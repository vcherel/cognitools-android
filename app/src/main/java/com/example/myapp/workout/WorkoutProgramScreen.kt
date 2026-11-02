package com.example.myapp.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.myapp.AddItemRow
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.LocalToolAccent
import com.example.myapp.ScreenTopBar
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ROW_HEIGHT = 64.dp

private fun ExerciseType.icon(): ImageVector = when (this) {
    ExerciseType.Reps -> Icons.Default.Repeat
    ExerciseType.Timed -> Icons.Default.Timer
}

private fun ExerciseType.label() = when (this) {
    ExerciseType.Reps -> "Reps"
    ExerciseType.Timed -> "Timed"
}

private fun formatRest(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

/** Which setting a number dialog edits. */
private enum class ProgramNumber(val title: String) {
    Rounds("Rounds"),
    Rest("Rest after an exercise (s)"),
    RoundRest("Rest between rounds (s)")
}

@Composable
fun WorkoutProgramScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by remember(context) { WorkoutStore.program(context) }.collectAsState(initial = null)
    // The list being edited, so a drag redraws at once; every change is written straight back.
    var program by remember { mutableStateOf<WorkoutProgram?>(null) }
    LaunchedEffect(saved) { if (saved != null) program = saved }
    var editing by remember { mutableStateOf<Int?>(null) }
    var editingNumber by remember { mutableStateOf<ProgramNumber?>(null) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    fun save(next: WorkoutProgram) {
        program = next
        scope.launch { WorkoutStore.setProgram(context, next) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Program", onBack = onBack)
        val current = program ?: return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 8.dp)
            ) {
                current.exercises.forEachIndexed { index, exercise ->
                    val dragging = index == draggedIndex
                    Row(
                        Modifier.fillMaxWidth().height(ROW_HEIGHT)
                            .zIndex(if (dragging) 1f else 0f)
                            .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                            .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { editing = index }
                            .padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.DragIndicator,
                            contentDescription = "Move",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp).padding(10.dp)
                                .pointerInput(index, current.exercises.size) {
                                    detectDragGestures(
                                        onDragStart = { draggedIndex = index; dragOffset = 0f },
                                        onDragEnd = {
                                            draggedIndex = -1
                                            dragOffset = 0f
                                            program?.let { save(it) }
                                        },
                                        onDragCancel = { draggedIndex = -1; dragOffset = 0f }
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val list = program?.exercises?.toMutableList() ?: return@detectDragGestures
                                        while (dragOffset > rowHeightPx / 2 && draggedIndex < list.size - 1) {
                                            list.add(draggedIndex + 1, list.removeAt(draggedIndex))
                                            draggedIndex++
                                            dragOffset -= rowHeightPx
                                        }
                                        while (dragOffset < -rowHeightPx / 2 && draggedIndex > 0) {
                                            list.add(draggedIndex - 1, list.removeAt(draggedIndex))
                                            draggedIndex--
                                            dragOffset += rowHeightPx
                                        }
                                        program = program?.copy(exercises = list)
                                    }
                                }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(exercise.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(
                                exercise.target,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            exercise.type.icon(),
                            contentDescription = exercise.type.label(),
                            tint = LocalToolAccent.current!!.color
                        )
                    }
                }
                AddItemRow(
                    onClick = { editing = current.exercises.size },
                    label = "New exercise",
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            WorkoutCard {
                ValueRow("Rounds", "${current.rounds}") { editingNumber = ProgramNumber.Rounds }
                ValueRow("Rest after an exercise", formatRest(current.restSeconds)) { editingNumber = ProgramNumber.Rest }
                ValueRow("Rest between rounds", formatRest(current.roundRestSeconds)) { editingNumber = ProgramNumber.RoundRest }
            }
        }

        editing?.let { index ->
            val existing = current.exercises.getOrNull(index)
            ExerciseDialog(
                initial = existing,
                onDismiss = { editing = null },
                onDelete = existing?.let {
                    {
                        editing = null
                        val before = current
                        save(current.copy(exercises = current.exercises.filterIndexed { i, _ -> i != index }))
                        AppSnackbar.show("${it.name} deleted", "Undo") { save(before) }
                    }
                },
                onConfirm = { exercise ->
                    editing = null
                    val list = current.exercises.toMutableList()
                    if (existing == null) list += exercise else list[index] = exercise
                    save(current.copy(exercises = list))
                }
            )
        }

        editingNumber?.let { field ->
            NumberDialog(
                title = field.title,
                initial = when (field) {
                    ProgramNumber.Rounds -> current.rounds
                    ProgramNumber.Rest -> current.restSeconds
                    ProgramNumber.RoundRest -> current.roundRestSeconds
                },
                min = if (field == ProgramNumber.Rounds) 1 else 0,
                onDismiss = { editingNumber = null },
                onConfirm = { value ->
                    editingNumber = null
                    save(
                        when (field) {
                            ProgramNumber.Rounds -> current.copy(rounds = value)
                            ProgramNumber.Rest -> current.copy(restSeconds = value)
                            ProgramNumber.RoundRest -> current.copy(roundRestSeconds = value)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun DigitsField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberDialog(title: String, initial: Int, min: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("$initial") }
    val value = text.toIntOrNull()
    AppDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        DigitsField("Value", text, { text = it })
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null && value >= min) { Text("Ok") }
        }
    }
}

@Composable
private fun ExerciseDialog(
    initial: Exercise?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onConfirm: (Exercise) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var target by remember { mutableStateOf(initial?.target.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: ExerciseType.Reps) }
    var seconds by remember { mutableStateOf(initial?.seconds?.takeIf { it > 0 }?.toString().orEmpty()) }

    val secondsValue = seconds.toIntOrNull() ?: 0
    val valid = name.isNotBlank() && (type != ExerciseType.Timed || secondsValue > 0)
    val accent = LocalToolAccent.current!!.color

    AppDialog(onDismiss = onDismiss) {
        Text("Exercise", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("Target") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ExerciseType.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { option ->
                        val on = option == type
                        Row(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { type = option },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val color = if (on) accent else MaterialTheme.colorScheme.onSurface
                            Icon(option.icon(), contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
                            Text(
                                option.label(),
                                color = color,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
            if (type == ExerciseType.Timed) DigitsField("Duration (s)", seconds, { seconds = it })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    enabled = valid,
                    onClick = {
                        onConfirm(
                            Exercise(
                                name = name.trim(),
                                target = target.trim(),
                                type = type,
                                seconds = if (type == ExerciseType.Timed) secondsValue else 0
                            )
                        )
                    }
                ) { Text("Ok") }
            }
        }
    }
}
