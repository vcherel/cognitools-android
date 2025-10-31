package com.example.myapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MyButton(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 24.sp,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDarkMode = LocalIsDarkMode.current

    // Adjust visual state based on dark mode, pressed state, and enabled state
    val shadowColor = remember(isPressed, enabled, isDarkMode) {
        when {
            !enabled -> if (isDarkMode) Color(0xFF424242) else Color(0xFF9E9E9E)
            isPressed -> Color(0xFF1565C0)
            else -> if (isDarkMode) Color(0xFF37474F) else Color(0xFFB0BEC5)
        }
    }

    val gradient = remember(isPressed, enabled, isDarkMode) {
        when {
            !enabled -> {
                if (isDarkMode) {
                    Brush.horizontalGradient(listOf(Color(0xFF424242), Color(0xFF303030)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFFBDBDBD), Color(0xFF9E9E9E)))
                }
            }
            isPressed -> {
                // Blue gradient when pressed (same for both modes)
                Brush.horizontalGradient(
                    listOf(Color(0xFF2196F3), Color(0xFF1976D2))
                )
            }
            else -> {
                if (isDarkMode) {
                    Brush.horizontalGradient(
                        listOf(Color(0xFF455A64), Color(0xFF37474F))
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(Color(0xFFECEFF1), Color(0xFFCFD8DC))
                    )
                }
            }
        }
    }

    val textColor = remember(isPressed, enabled, isDarkMode) {
        when {
            !enabled -> if (isDarkMode) Color(0xFF9E9E9E) else Color(0xFF757575)
            isPressed -> Color.White
            else -> if (isDarkMode) Color.White else Color.Black
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .scale(if (isPressed) 0.98f else 1f)
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
    ) {
        // Shadow layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 6.dp)
                .background(
                    color = shadowColor,
                    shape = RoundedCornerShape(35)
                )
        )

        // Main button
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = gradient,
                    shape = RoundedCornerShape(35)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = textColor,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Text(
                    text,
                    color = textColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun MySwitch(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDarkMode = LocalIsDarkMode.current

    val boxWidth = if (text != null) 200.dp else 85.dp
    val boxHeight = if (text != null) 100.dp else 55.dp

    val shadowColor = remember(isEnabled, isDarkMode) {
        if (isEnabled) {
            Color(0xFF1565C0)
        } else {
            if (isDarkMode) Color(0xFF424242) else Color(0xFFB0BEC5)
        }
    }

    val gradient = remember(isEnabled, isDarkMode) {
        if (isEnabled) {
            Brush.horizontalGradient(
                listOf(Color(0xFF2196F3), Color(0xFF1976D2))
            )
        } else {
            if (isDarkMode) {
                Brush.horizontalGradient(
                    listOf(Color(0xFF455A64), Color(0xFF37474F))
                )
            } else {
                Brush.horizontalGradient(
                    listOf(Color(0xFFECEFF1), Color(0xFFCFD8DC))
                )
            }
        }
    }

    val textColor = remember(isEnabled, isDarkMode) {
        if (isEnabled) {
            Color.White
        } else {
            if (isDarkMode) Color.White else Color.Black
        }
    }

    Box(
        modifier = modifier
            .width(boxWidth)
            .height(boxHeight)
            .scale(if (isPressed) 0.98f else 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 5.dp)
                .background(
                    color = shadowColor,
                    shape = RoundedCornerShape(40)
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = gradient,
                    shape = RoundedCornerShape(40)
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onToggle(!isEnabled) }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (text != null) {
                    Text(
                        text = text,
                        color = textColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF0D47A1),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = if (isDarkMode) Color(0xFF37474F) else Color(0xFFB0BEC5)
                    ),
                    modifier = Modifier.scale(1.2f) // slightly bigger
                )
            }
        }
    }
}

@Composable
fun ShowAlertDialog(
    onDismiss: () -> Unit,
    title: String,
    show: Boolean = true,
    textContent: (@Composable () -> Unit)? = null,
    confirmText: String = "Ok",
    cancelText: String? = "Annuler",
    onConfirm: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    if (show) {
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
                            fontSize = 14.sp
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
}