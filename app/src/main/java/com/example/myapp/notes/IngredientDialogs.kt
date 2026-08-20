package com.example.myapp.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapp.AppDialog

// The dialogs the sync flows put in front of Valentin: reconciling an item the model doesn't
// know, and the plain name prompt for adding one by hand. The logic they drive lives in
// IngredientSync.kt, the flows that open them in NoteSyncActions.kt.

private enum class ReconcileStep { Choose, Group, NewGroup, Existing }

/**
 * Shown for an item whose name isn't in the model. Lets the user add it as a new entry
 * (choosing which group, or a new group when `allowNewGroup`), map it to an existing model
 * entry (for a misspelling or variant spelling), or skip it. Within the chosen group the
 * entry is placed alphabetically, so only the group needs picking. `groupLabels`, when
 * given, names each group in the picker instead of listing its members. `onAddNewGroup`,
 * when given, adds a "Nouvelle catégorie" choice: a name to type and a position in the
 * existing order (used by Courses, whose groups are named sections).
 */
@Composable
fun IngredientReconcileDialog(
    itemName: String,
    groups: List<List<String>>,
    groupLabels: List<String>? = null,
    allowNewGroup: Boolean = true,
    onAddNewGroup: ((name: String, beforeIndex: Int) -> Unit)? = null,
    onAddNew: (groupIndex: Int) -> Unit,
    onMapExisting: (String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember(itemName) { mutableStateOf(ReconcileStep.Choose) }

    AppDialog(onDismiss = onDismiss) {
        when (step) {
            ReconcileStep.Choose -> {
                Text("« $itemName »", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cet article n'est pas reconnu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                ChoiceRow("Ajouter au modèle", Icons.Default.Add) {
                    step = ReconcileStep.Group
                }
                ChoiceRow("C'est déjà dans le modèle", Icons.Default.Edit) {
                    step = ReconcileStep.Existing
                }
                ChoiceRow("Ignorer", Icons.Default.Close, onSkip)
            }

            ReconcileStep.Group -> {
                Text("Dans quel groupe ?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "« $itemName » sera placé par ordre alphabétique dans le groupe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(groups) { i, g ->
                        PositionRow(groupLabels?.getOrNull(i)?.ifEmpty { null } ?: g.joinToString(", ")) { onAddNew(i) }
                        HorizontalDivider()
                    }
                    if (allowNewGroup) {
                        item {
                            PositionRow("Nouveau groupe", bold = true) { onAddNew(-1) }
                        }
                    }
                    if (onAddNewGroup != null) {
                        item {
                            PositionRow("Nouvelle catégorie", bold = true) { step = ReconcileStep.NewGroup }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BackRow { step = ReconcileStep.Choose }
            }

            ReconcileStep.NewGroup -> {
                var name by remember { mutableStateOf("") }
                Text("Nouvelle catégorie", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Placer avant :",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(groups) { i, g ->
                        PositionRow(groupLabels?.getOrNull(i)?.ifEmpty { null } ?: g.joinToString(", ")) {
                            if (name.isNotBlank()) onAddNewGroup?.invoke(name.trim(), i)
                        }
                        HorizontalDivider()
                    }
                    item {
                        PositionRow("À la fin", bold = true) {
                            if (name.isNotBlank()) onAddNewGroup?.invoke(name.trim(), groups.size)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BackRow { step = ReconcileStep.Group }
            }

            ReconcileStep.Existing -> {
                var query by remember { mutableStateOf("") }
                Text("Lequel ?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                val ranked = rankIngredientsByCloseness(groups.flatten(), query.ifBlank { itemName })
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(ranked) { ingredient ->
                        PositionRow(ingredient) { onMapExisting(ingredient) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BackRow { step = ReconcileStep.Choose }
            }
        }
    }
}

/** Simple name prompt for adding an item directly to the Ingrédients or Courses note. */
@Composable
fun AddIngredientNameDialog(
    title: String = "Ajouter un ingrédient",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AppDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Nom") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Annuler") }
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Ajouter") }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PositionRow(label: String, bold: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun BackRow(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text("Retour")
    }
}
