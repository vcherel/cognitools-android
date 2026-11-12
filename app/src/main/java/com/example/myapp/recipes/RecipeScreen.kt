package com.example.myapp.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.AddItemRow
import com.example.myapp.AppDialog
import com.example.myapp.AppSnackbar
import com.example.myapp.ErrorText
import com.example.myapp.LightIconButton
import com.example.myapp.MyButton
import com.example.myapp.ScreenTopBar
import com.example.myapp.deaccented
import com.example.myapp.notes.courseItemName
import com.example.myapp.notes.itemKey
import com.example.myapp.plural
import com.example.myapp.recipeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The latest recipe the laptop sent, as one scroll: its ingredients, the ones the Ingrédients note does
 * not list marked missing, then its steps to tick off, a step naming a duration offering a timer for
 * it. The screen stays on while it is open. Cooked opens the dialog that takes what was used out of
 * Ingrédients (or comments it there) and, if wanted, onto Courses.
 */
@Composable
fun RecipeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.recipeRepository
    val recipe by repo.recipe.collectAsState()
    val done by repo.done.collectAsState()
    val stock by repo.stock.collectAsState()
    val loading by repo.loading.collectAsState()
    val error by repo.error.collectAsState()
    val pantry by repo.pantry.collectAsState()
    val timers by repo.timers.timers.collectAsState()
    val scope = rememberCoroutineScope()
    var cooking by remember { mutableStateOf(false) }
    var settingTimer by remember { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Recipes",
            onBack = onBack,
            titleStyle = MaterialTheme.typography.titleLarge,
            titleWeight = true
        ) {
            IconButton(onClick = { settingTimer = true }) {
                Icon(Icons.Filled.Timer, contentDescription = "Timer")
            }
            if (loading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = repo::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        error?.let {
            ErrorText(message = "Error: $it", onDismiss = repo::clearError, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (timers.isNotEmpty()) TimerRows(timers, onStop = repo.timers::stop)

        if (settingTimer) {
            TimerDialog(
                onDismiss = { settingTimer = false },
                onStart = { minutes ->
                    settingTimer = false
                    repo.timers.start("Timer", minutes * 60)
                }
            )
        }

        val shown = recipe
        if (shown == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Loading the recipe…" else "No recipe yet. Refresh to fetch it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        val missing = shown.ingredients.filter { it.name.itemKey() !in stock }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(shown.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${shown.servings} servings, ${formatMinutes(shown.minutes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (shown.pitch.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(shown.pitch, style = MaterialTheme.typography.bodyLarge)
            }

            SectionTitle("Ingredients")
            shown.ingredients.forEach { ingredient ->
                val isMissing = ingredient in missing
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ingredient.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        ingredient.quantity,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMissing) {
                        Spacer(Modifier.width(8.dp))
                        Text("missing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MyButton(text = "Add missing to Courses", height = 52.dp, fontSize = 16.sp) {
                    scope.launch {
                        val undo = repo.addToCourses(missing.map { it.name }) ?: return@launch
                        AppSnackbar.show("${missing.size} item${plural(missing.size)} added to Courses", "Undo") { repo.undo(undo) }
                    }
                }
            }

            SectionTitle("Steps")
            shown.steps.forEachIndexed { index, step ->
                val ticked = index in done
                Row(
                    Modifier.fillMaxWidth().clickable { repo.toggleStep(index) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(checked = ticked, onCheckedChange = { repo.toggleStep(index) })
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        Text(
                            "${index + 1}. $step",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (ticked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (ticked) TextDecoration.LineThrough else null
                        )
                        val durations = remember(step) { stepDurations(step) }
                        if (durations.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                durations.forEach { seconds ->
                                    AssistChip(
                                        onClick = { repo.timers.start("Step ${index + 1}", seconds) },
                                        label = { Text(formatTimerLength(seconds)) },
                                        leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null, Modifier.size(18.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            MyButton(text = "Cooked", height = 60.dp, fontSize = 18.sp) { cooking = true }
            Spacer(Modifier.height(24.dp))
        }

        if (cooking) {
            CookedDialog(
                recipe = shown,
                used = shown.ingredients.map { it.name }.filter { it.itemKey() in stock },
                pantry = pantry.map { it.courseItemName() }.distinctBy { it.itemKey() },
                currentComments = pantry.associate { it.courseItemName().itemKey() to ingredientComment(it) },
                onDismiss = { cooking = false },
                onApply = { delete, comments, groceries, feedback ->
                    cooking = false
                    if (feedback.isNotBlank()) repo.comment(shown, feedback)
                    scope.launch {
                        val undo = repo.edit(delete, comments, groceries)
                        if (undo == null) {
                            if (feedback.isNotBlank()) AppSnackbar.show("Comment sent")
                            return@launch
                        }
                        val parts = listOfNotNull(
                            "comment sent".takeIf { feedback.isNotBlank() },
                            "${delete.size} removed".takeIf { delete.isNotEmpty() },
                            comments.count { it.value.isNotBlank() }.takeIf { it > 0 }?.let { "$it commented" },
                            comments.count { it.value.isBlank() }.takeIf { it > 0 }?.let { "$it comment${plural(it)} removed" },
                            "${groceries.size} added to Courses".takeIf { groceries.isNotEmpty() }
                        )
                        AppSnackbar.show(parts.joinToString(", ").replaceFirstChar { it.uppercase() }, "Undo") { repo.undo(undo) }
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

private fun formatMinutes(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h" + (minutes % 60).takeIf { it > 0 }?.let { " %02d".format(it) }.orEmpty()

/** The running and ringing timers, pinned under the header so they stay in view while scrolling. */
@Composable
private fun TimerRows(timers: List<CookingTimer>, onStop: (Int) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        timers.forEach { timer ->
            val left = timer.endAt - now
            val ringing = left <= 0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(timer.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    if (ringing) "Time is up" else formatCountdown(left),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (ringing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (ringing) {
                    TextButton(onClick = { onStop(timer.id) }) { Text("Stop") }
                } else {
                    IconButton(onClick = { onStop(timer.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                }
            }
        }
    }
}

private fun formatCountdown(millis: Long): String {
    val seconds = (millis + 999) / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun TimerDialog(onDismiss: () -> Unit, onStart: (minutes: Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val minutes = text.toIntOrNull()?.takeIf { it > 0 }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppDialog(onDismiss = onDismiss) {
        Text("Timer", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value.filter(Char::isDigit).take(3) },
            label = { Text("Minutes") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { minutes?.let(onStart) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { minutes?.let(onStart) }, enabled = minutes != null) { Text("Start") }
        }
    }
}

/** The comment an ingredient gets in parentheses in Ingrédients; the Cooked dialog and that note both open it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommentDialog(name: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AppDialog(onDismiss = onDismiss) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COMMENT_CHOICES.forEach { choice ->
                FilterChip(
                    selected = choice in commentParts(text),
                    onClick = { text = toggledComment(text, choice) },
                    label = { Text(choice) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value.filter { it != '(' && it != ')' && it != '\n' } },
            label = { Text("Comment") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text.trim()) }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onSave(text.trim()) }) { Text("Save") }
        }
    }
}

/**
 * One row per ingredient used, the recipe's own plus any added from Ingrédients: its name or Comment
 * opens the comment it gets in parentheses there (shown under the name, its cross clearing it), Delete takes it out, Groceries also puts it on
 * Courses (and implies Delete unless it is kept with a comment). The recipe comment below goes to the
 * laptop's `/recipes` skill.
 */
@Composable
private fun CookedDialog(
    recipe: Recipe,
    used: List<String>,
    pantry: List<String>,
    currentComments: Map<String, String>,
    onDismiss: () -> Unit,
    onApply: (delete: List<String>, comments: Map<String, String>, groceries: List<String>, feedback: String) -> Unit
) {
    var feedback by remember { mutableStateOf("") }
    val extras = remember { mutableStateListOf<String>() }
    val delete = remember { mutableStateMapOf<String, Boolean>() }
    val comments = remember { mutableStateMapOf<String, String>() }
    val groceries = remember { mutableStateMapOf<String, Boolean>() }
    var picking by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf<String?>(null) }
    val rows = used + extras
    fun commentOf(name: String) = comments[name] ?: currentComments[name.itemKey()].orEmpty()
    fun keptWithComment(name: String) = !comments[name].isNullOrBlank()
    AppDialog(onDismiss = onDismiss) {
        Text("Cooked: ${recipe.title}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            ColumnLabel("Comment")
            ColumnLabel("Delete")
            ColumnLabel("Groceries")
        }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            rows.forEach { name ->
                val comment = commentOf(name)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { commenting = name }.padding(vertical = 4.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        if (comment.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "($comment)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                LightIconButton(
                                    icon = Icons.Filled.Close,
                                    contentDescription = "Remove comment",
                                    size = 28.dp,
                                    iconSize = 16.dp,
                                    onClick = { comments[name] = "" }
                                )
                            }
                        }
                    }
                    Box(Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { commenting = name }) {
                            if (comment.isBlank()) {
                                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comment")
                            } else {
                                Icon(Icons.Filled.ChatBubble, contentDescription = "Comment", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Box(Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                        Checkbox(checked = delete[name] == true, onCheckedChange = { checked ->
                            delete[name] = checked
                            if (checked) comments.remove(name)
                            else if (!keptWithComment(name)) groceries[name] = false
                        })
                    }
                    Box(Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                        Checkbox(checked = groceries[name] == true, onCheckedChange = { checked ->
                            groceries[name] = checked
                            if (checked && !keptWithComment(name)) delete[name] = true
                        })
                    }
                }
            }
            val addable = pantry.filter { candidate -> rows.none { it.itemKey() == candidate.itemKey() } }
            if (addable.isNotEmpty()) AddItemRow(onClick = { picking = true }, label = "Add ingredient")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = feedback,
            onValueChange = { feedback = it },
            label = { Text("Recipe comment") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = {
                onApply(
                    rows.filter { delete[it] == true },
                    comments.filterKeys { it in rows },
                    rows.filter { groceries[it] == true },
                    feedback.trim()
                )
            }) { Text("Apply") }
        }
    }
    commenting?.let { name ->
        CommentDialog(
            name = name,
            initial = commentOf(name),
            onDismiss = { commenting = null },
            onSave = { comment ->
                commenting = null
                comments[name] = comment
                delete[name] = false
            }
        )
    }
    if (picking) {
        PantryPicker(
            names = pantry.filter { candidate -> rows.none { it.itemKey() == candidate.itemKey() } },
            onDismiss = { picking = false },
            onPick = { name ->
                picking = false
                extras.add(name)
            }
        )
    }
}

/** The Ingrédients note's items, filtered as typed, to add one the recipe did not list. */
@Composable
private fun PantryPicker(names: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = names.filter { it.deaccented().contains(query.trim().deaccented()) }
    AppDialog(onDismiss = onDismiss) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Ingredient") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(name) }.padding(vertical = 10.dp)
                )
            }
        }
    }
}

private val COLUMN_WIDTH = 60.dp

@Composable
private fun ColumnLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(COLUMN_WIDTH)
    )
}
