package com.example.myapp.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.myapp.BackIconButton

/** Which of the editor's title-bar actions apply to the note currently open. */
data class NoteEditorBarState(
    val isEditing: Boolean,
    val locked: Boolean,
    val searchOpen: Boolean,
    val canUndo: Boolean,
    val hasBlankEdgeLines: Boolean,
    val hasCheckboxLine: Boolean,
    val hasCheckedLine: Boolean,
    val isCoursesNote: Boolean,
    val isIngredientsNote: Boolean,
    val isIngredientModelNote: Boolean
)

/** What the bar's buttons and menu entries do. All of them act on the note the editor holds. */
data class NoteEditorBarActions(
    val onBack: () -> Unit,
    val onUndo: () -> Unit,
    val onToggleSearch: () -> Unit,
    val onToggleLock: () -> Unit,
    val onTrimBlankEdgeLines: () -> Unit,
    val onSendCheckedToIngredients: () -> Unit,
    val onAddIngredient: () -> Unit,
    val onAddCourseItem: () -> Unit,
    val onResortIngredients: () -> Unit,
    val onResortCourses: () -> Unit,
    val onSetAllCheckboxes: (Boolean) -> Unit,
    val onRemoveChecked: () -> Unit,
    val onToggleInlineMarker: (String) -> Unit,
    val onToggleTitle: () -> Unit
)

/**
 * The note editor's header: back arrow, the editable title, and the actions that apply right now.
 * Focusing the title hides every button so the whole width goes to the text being typed.
 */
@Composable
fun NoteEditorTopBar(
    titleFieldState: TextFieldState,
    state: NoteEditorBarState,
    actions: NoteEditorBarActions,
    titleFocused: Boolean,
    onTitleFocusChanged: (Boolean) -> Unit
) {
    var showFormatMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!titleFocused) {
            BackIconButton(onBack = actions.onBack)
        }
        BasicTextField(
            state = titleFieldState,
            inputTransformation = stripNewlinesTransformation,
            // Wraps onto up to two lines rather than being clipped by the buttons;
            // stripNewlinesTransformation still keeps it a single logical line.
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
                .onFocusChanged { onTitleFocusChanged(it.isFocused) },
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onBackground
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
            decorator = { innerTextField ->
                Box {
                    if (titleFieldState.text.isEmpty()) {
                        Text("Note", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    }
                    innerTextField()
                }
            }
        )
        if (titleFocused) return@Row

        if (!state.isEditing) {
            if (state.hasBlankEdgeLines) {
                IconButton(onClick = actions.onTrimBlankEdgeLines) {
                    Icon(Icons.Default.VerticalAlignCenter, contentDescription = "Supprimer les lignes vides en haut/bas")
                }
            }
            if (state.isCoursesNote && state.hasCheckedLine) {
                IconButton(onClick = actions.onSendCheckedToIngredients) {
                    Icon(Icons.Default.Kitchen, contentDescription = "Ranger les articles cochés dans les Ingrédients")
                }
            }
            if (state.isIngredientsNote) {
                IconButton(onClick = actions.onAddIngredient) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter un ingrédient")
                }
            }
            if (state.isIngredientModelNote) {
                IconButton(onClick = actions.onResortIngredients) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Réordonner les ingrédients présents")
                }
            }
            if (state.isCoursesNote) {
                IconButton(onClick = actions.onAddCourseItem) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter un article")
                }
            }
            IconButton(onClick = actions.onToggleSearch) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Rechercher dans la note",
                    tint = if (state.searchOpen) MaterialTheme.colorScheme.primary
                    else LocalContentColor.current
                )
            }
        }
        IconButton(onClick = actions.onUndo, enabled = state.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Annuler")
        }
        if (state.isEditing) {
            Box {
                IconButton(onClick = { showFormatMenu = true }) {
                    Icon(Icons.Default.FormatBold, contentDescription = "Mise en forme")
                }
                DropdownMenu(expanded = showFormatMenu, onDismissRequest = { showFormatMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Gras") },
                        leadingIcon = { Icon(Icons.Default.FormatBold, contentDescription = null) },
                        onClick = { actions.onToggleInlineMarker("**") }
                    )
                    DropdownMenuItem(
                        text = { Text("Italique") },
                        leadingIcon = { Icon(Icons.Default.FormatItalic, contentDescription = null) },
                        onClick = { actions.onToggleInlineMarker("*") }
                    )
                    DropdownMenuItem(
                        text = { Text("Souligné") },
                        leadingIcon = { Icon(Icons.Default.FormatUnderlined, contentDescription = null) },
                        onClick = { actions.onToggleInlineMarker("__") }
                    )
                    DropdownMenuItem(
                        text = { Text("Titre") },
                        leadingIcon = { Icon(Icons.Default.Title, contentDescription = null) },
                        onClick = { actions.onToggleTitle() }
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { showMoreMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options")
            }
            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (state.locked) "Déverrouiller" else "Verrouiller") },
                    leadingIcon = {
                        Icon(
                            if (state.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null
                        )
                    },
                    onClick = { showMoreMenu = false; actions.onToggleLock() }
                )
                if (state.isEditing) return@DropdownMenu
                if (state.hasCheckboxLine) {
                    DropdownMenuItem(
                        text = { Text("Tout cocher") },
                        leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                        onClick = { showMoreMenu = false; actions.onSetAllCheckboxes(true) }
                    )
                    DropdownMenuItem(
                        text = { Text("Tout décocher") },
                        leadingIcon = { Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = null) },
                        onClick = { showMoreMenu = false; actions.onSetAllCheckboxes(false) }
                    )
                    if (state.hasCheckedLine) {
                        DropdownMenuItem(
                            text = { Text("Supprimer les cochés") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                            onClick = { showMoreMenu = false; actions.onRemoveChecked() }
                        )
                    }
                }
                if (state.isCoursesNote) {
                    DropdownMenuItem(
                        text = { Text("Mettre à jour selon le modèle") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                        onClick = { showMoreMenu = false; actions.onResortCourses() }
                    )
                }
            }
        }
    }
}
