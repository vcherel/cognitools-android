package com.example.myapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class ButtonStyle(val shadow: Color, val gradient: Brush, val text: Color)

private fun buttonStyle(isPressed: Boolean, enabled: Boolean, isDarkMode: Boolean): ButtonStyle = when {
    !enabled -> ButtonStyle(
        shadow = if (isDarkMode) Color(0xFF424242) else Color(0xFF9E9E9E),
        gradient = if (isDarkMode) {
            Brush.horizontalGradient(listOf(Color(0xFF424242), Color(0xFF303030)))
        } else {
            Brush.horizontalGradient(listOf(Color(0xFFBDBDBD), Color(0xFF9E9E9E)))
        },
        text = if (isDarkMode) Color(0xFF9E9E9E) else Color(0xFF757575)
    )
    isPressed -> ButtonStyle(
        shadow = Color(0xFF1565C0),
        gradient = Brush.horizontalGradient(listOf(Color(0xFF2196F3), Color(0xFF1976D2))),
        text = Color.White
    )
    else -> ButtonStyle(
        shadow = if (isDarkMode) Color(0xFF37474F) else Color(0xFFB0BEC5),
        gradient = if (isDarkMode) {
            Brush.horizontalGradient(listOf(Color(0xFF455A64), Color(0xFF37474F)))
        } else {
            Brush.horizontalGradient(listOf(Color(0xFFECEFF1), Color(0xFFCFD8DC)))
        },
        text = if (isDarkMode) Color.White else Color.Black
    )
}

// The raised, gradient-filled surface every custom button and switch is built from:
// a drop-shadow layer under a gradient layer, pressed down slightly while touched.
@Composable
private fun RaisedSurface(
    style: ButtonStyle,
    isPressed: Boolean,
    modifier: Modifier = Modifier,
    shadowOffset: Dp = 6.dp,
    cornerPercent: Int = 35,
    clickModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .scale(if (isPressed) 0.98f else 1f)
            .then(clickModifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = shadowOffset)
                .background(style.shadow, RoundedCornerShape(cornerPercent))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(style.gradient, RoundedCornerShape(cornerPercent)),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

@Composable
fun MyButton(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 24.sp,
    height: Dp = 90.dp,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDarkMode = LocalIsDarkMode.current

    val style = remember(isPressed, enabled, isDarkMode) {
        buttonStyle(isPressed, enabled, isDarkMode)
    }

    RaisedSurface(
        style = style,
        isPressed = isPressed,
        modifier = modifier.fillMaxWidth().height(height),
        clickModifier = if (enabled) Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        ) else Modifier
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = style.text,
                modifier = Modifier.size(32.dp)
            )
        } else {
            Text(
                text,
                color = style.text,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SplitMyButton(
    text: String,
    rightIcon: ImageVector,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    rightLoading: Boolean = false,
    onMainClick: () -> Unit,
    onRightClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SplitButtonHalf(
            modifier = Modifier.weight(1f),
            height = height,
            onClick = onMainClick
        ) { textColor ->
            Text(
                text,
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        SplitButtonHalf(
            modifier = Modifier.width(80.dp),
            height = height,
            onClick = onRightClick
        ) { textColor ->
            if (rightLoading) {
                CircularProgressIndicator(
                    color = textColor,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(
                    imageVector = rightIcon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun SplitButtonHalf(
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    onClick: () -> Unit,
    content: @Composable (textColor: Color) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDarkMode = LocalIsDarkMode.current
    val style = remember(isPressed, isDarkMode) {
        buttonStyle(isPressed, enabled = true, isDarkMode = isDarkMode)
    }

    RaisedSurface(
        style = style,
        isPressed = isPressed,
        modifier = modifier.height(height),
        clickModifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    ) {
        content(style.text)
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

    // The "pressed" style doubles as the "switch on" look.
    val style = remember(isEnabled, isDarkMode) {
        buttonStyle(isPressed = isEnabled, enabled = true, isDarkMode = isDarkMode)
    }

    RaisedSurface(
        style = style,
        isPressed = isPressed,
        modifier = modifier.width(boxWidth).height(boxHeight),
        shadowOffset = 5.dp,
        cornerPercent = 40,
        clickModifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null
        ) { onToggle(!isEnabled) }
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
                    color = style.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Display only: the whole surface handles the toggle.
            Switch(
                checked = isEnabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF0D47A1),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = if (isDarkMode) Color(0xFF37474F) else Color(0xFFB0BEC5)
                ),
                modifier = Modifier.scale(1.2f)
            )
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