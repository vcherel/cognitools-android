package com.example.myapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun ShowAlertDialog(
    onDismiss: () -> Unit,
    title: String,
    textContent: (@Composable () -> Unit)? = null,
    confirmText: String = "Ok",
    cancelText: String? = "Cancel",
    onConfirm: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = textContent,
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onCancel != null && cancelText != null) {
                    MyButton(
                        text = cancelText,
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(50.dp),
                        fontSize = 14.sp,
                        secondary = true
                    )
                }
                MyButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(50.dp),
                    fontSize = 14.sp
                )
            }
        }
    )
}

// The shell every custom dialog in the app is built from: a rounded, elevated surface with the
// standard padding. Callers fill the column.
@Composable
fun AppDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                content = content
            )
        }
    }
}
