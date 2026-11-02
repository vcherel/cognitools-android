package com.example.myapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/**
 * How long the pressed look stays on screen at the least. Inside a scrollable container Compose
 * holds a press back until it is sure the gesture isn't a scroll, so a quick tap used to emit its
 * press and its release together and the blue never showed at all.
 */
private const val MIN_PRESSED_MS = 120L

/** The pressed state, held for at least [MIN_PRESSED_MS], so every tap is seen. */
@Composable
fun rememberHeldPressed(interactionSource: InteractionSource): State<Boolean> {
    val pressed = remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        var pressedAt = 0L
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressedAt = System.currentTimeMillis()
                    pressed.value = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val shown = System.currentTimeMillis() - pressedAt
                    if (shown < MIN_PRESSED_MS) delay(MIN_PRESSED_MS - shown)
                    pressed.value = false
                }
            }
        }
    }
    return pressed
}

private class ButtonStyle(val shadow: Color, val gradient: Brush, val text: Color)

private fun buttonStyle(
    isPressed: Boolean,
    enabled: Boolean,
    isDarkMode: Boolean,
    highlighted: Boolean,
    tint: Color? = null
): ButtonStyle = when {
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
    highlighted -> ButtonStyle(
        shadow = Color(0xFFFF8F00),
        gradient = Brush.horizontalGradient(listOf(Color(0xFFFFCA28), Color(0xFFFFB300))),
        text = Color.Black
    )
    // The default surface washed with [tint], its edge a deeper shade of it.
    tint != null -> ButtonStyle(
        shadow = lerp(if (isDarkMode) Color(0xFF263238) else Color(0xFFB0BEC5), tint, 0.45f),
        gradient = if (isDarkMode) {
            Brush.horizontalGradient(listOf(lerp(Color(0xFF455A64), tint, 0.22f), lerp(Color(0xFF37474F), tint, 0.22f)))
        } else {
            Brush.horizontalGradient(listOf(lerp(Color(0xFFECEFF1), tint, 0.16f), lerp(Color(0xFFCFD8DC), tint, 0.16f)))
        },
        text = if (isDarkMode) lerp(tint, Color.White, 0.3f) else lerp(tint, Color.Black, 0.2f)
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

/** A tool's icon on a small square tinted with its [color], drawn before a button's label. */
@Composable
internal fun IconChip(
    icon: ImageVector,
    color: Color,
    size: Dp,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val isDarkMode = LocalIsDarkMode.current
    val background = when {
        highlighted -> Color.White.copy(alpha = 0.45f)
        isDarkMode -> lerp(Color(0xFF263238), color, 0.3f)
        else -> lerp(Color.White, color, 0.18f)
    }
    val tint = when {
        highlighted -> Color.Black
        isDarkMode -> lerp(color, Color.White, 0.3f)
        else -> lerp(color, Color.Black, 0.2f)
    }
    Box(
        modifier = modifier.size(size).background(background, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.62f))
    }
}

// The label centered, or with a chip, chip and label from the start edge. The chip scales with the text.
@Composable
private fun ButtonLabel(
    text: String,
    textColor: Color,
    fontSize: TextUnit,
    chipIcon: ImageVector?,
    chipColor: Color,
    highlighted: Boolean
) {
    if (chipIcon == null) {
        Text(
            text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        return
    }
    val chipSize = (fontSize.value * 1.85f).dp
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = chipSize * 0.4f, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(chipIcon, chipColor, chipSize, highlighted)
        Spacer(modifier = Modifier.width(chipSize * 0.35f))
        Text(
            text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
    highlighted: Boolean = false,
    icon: ImageVector? = null,
    chipIcon: ImageVector? = null,
    chipColor: Color = Color.Gray,
    /** Keeps the button gray inside a tool, whose buttons otherwise take its color (Cancel, steppers). */
    secondary: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    RaisedButton(
        modifier = modifier.fillMaxWidth(),
        height = height,
        enabled = enabled,
        highlighted = highlighted,
        tint = if (secondary) null else LocalToolAccent.current?.color,
        onLongClick = onLongClick,
        onClick = onClick
    ) { textColor ->
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = textColor,
                modifier = Modifier.size(32.dp)
            )
        } else {
            ButtonLabel(text, textColor, fontSize, chipIcon, chipColor, highlighted)
        }
    }
}

/**
 * A main button with a narrow action button beside it. [height] unspecified fills the height the
 * caller gives the row; [rightTint] colors the action button.
 */
@Composable
fun SplitMyButton(
    text: String,
    modifier: Modifier = Modifier,
    rightIcon: ImageVector? = null,
    rightText: String? = null,
    height: Dp = 90.dp,
    rightWidth: Dp = 80.dp,
    fontSize: TextUnit = 24.sp,
    rightLoading: Boolean = false,
    rightEnabled: Boolean = true,
    chipIcon: ImageVector? = null,
    chipColor: Color = Color.Gray,
    rightTint: Color? = null,
    onMainLongClick: (() -> Unit)? = null,
    onMainClick: () -> Unit,
    onRightClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaisedButton(
            modifier = Modifier.weight(1f),
            height = height,
            onLongClick = onMainLongClick,
            onClick = onMainClick
        ) { textColor ->
            ButtonLabel(text, textColor, fontSize, chipIcon, chipColor, highlighted = false)
        }

        RaisedButton(
            modifier = Modifier.width(rightWidth),
            height = height,
            enabled = rightEnabled,
            tint = rightTint,
            onClick = onRightClick
        ) { textColor ->
            if (rightLoading) {
                CircularProgressIndicator(
                    color = textColor,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp)
                )
            } else if (rightText != null) {
                Text(
                    rightText,
                    color = textColor,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (rightIcon != null) {
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

// A raised surface that behaves as a button: the pressed style and the click handling every
// button in the app shares, with the caller drawing whatever goes inside.
@Composable
private fun RaisedButton(
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    tint: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable (textColor: Color) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by rememberHeldPressed(interactionSource)
    val isDarkMode = LocalIsDarkMode.current
    val haptics = LocalHapticFeedback.current
    val style = remember(isPressed, enabled, isDarkMode, highlighted, tint) {
        buttonStyle(isPressed, enabled = enabled, isDarkMode = isDarkMode, highlighted = highlighted, tint = tint)
    }

    RaisedSurface(
        style = style,
        isPressed = isPressed,
        modifier = modifier.then(if (height.isSpecified) Modifier.height(height) else Modifier.fillMaxHeight()),
        clickModifier = if (enabled) Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onLongClick = onLongClick?.let { longClick ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                }
            },
            onClick = onClick
        ) else Modifier
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
    val isPressed by rememberHeldPressed(interactionSource)
    val isDarkMode = LocalIsDarkMode.current

    val boxWidth = if (text != null) 200.dp else 85.dp
    val boxHeight = if (text != null) 100.dp else 55.dp

    // On reads as the tool's tint inside a tool, else as the blue "pressed" style.
    val accent = LocalToolAccent.current?.color
    val style = remember(isEnabled, isDarkMode, accent) {
        if (isEnabled && accent != null) {
            buttonStyle(isPressed = false, enabled = true, isDarkMode = isDarkMode, highlighted = false, tint = accent)
        } else {
            buttonStyle(isPressed = isEnabled, enabled = true, isDarkMode = isDarkMode, highlighted = false)
        }
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
                    checkedTrackColor = accent ?: Color(0xFF0D47A1),
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

// A red warning message with a close button, so the user can clear it without waiting for the
// underlying state to change or leaving the screen.
@Composable
fun ErrorText(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        // The exact wording is what a bug report needs, and a release build's message can be
        // long and unreadable on screen.
        IconButton(onClick = { copyToClipboard(context, message, "Error") }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.error)
        }
    }
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
/**
 * An IconButton drawn as one node: the icon itself is the clickable, padded out to [size]. Same
 * look, touch target and round ripple, but a screen repeating it per card or per line (the notes
 * list, the note view) composes and lays out a fraction of what IconButton costs.
 */
@Composable
fun LightIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 24.dp,
    tint: Color = Color.Unspecified
) {
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = tint.takeOrElse { LocalContentColor.current },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = size / 2),
                role = Role.Button,
                onClick = onClick
            )
            .padding((size - iconSize) / 2)
    )
}

/**
 * A one node checkbox for long lists of items (the Todo widget, a note's lines): the Material one
 * animates and lays out several nodes per box, which a list of dozens pays for on every frame.
 */
@Composable
fun LightCheckbox(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Icon(
        if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
        contentDescription = null,
        tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(48.dp)
            .toggleable(
                value = checked,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 20.dp),
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(12.dp)
    )
}

/** The gray "+ New item" placeholder sitting where a new entry would go in a list. */
@Composable
fun AddItemRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "New item"
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Gray, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}
