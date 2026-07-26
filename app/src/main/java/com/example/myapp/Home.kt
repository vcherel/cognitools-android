package com.example.myapp

import android.os.SystemClock
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// Jumps back to the main menu from anywhere. Provided by MainScreen.
val LocalGoHome = compositionLocalOf<() -> Unit> { {} }

// Tap goes back, long press goes straight to the main menu.
// Every back arrow in the app uses this so the shortcut works on every screen.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BackIconButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Retour"
) {
    val goHome = LocalGoHome.current
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onBack,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    goHome()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = contentDescription)
    }
}

// Time away from the app after which coming back lands on the main menu.
private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

// Screens that would lose something on an automatic reset hold this while they are open.
class IdleResetGuard {
    private var holders by mutableIntStateOf(0)

    val suppressed: Boolean get() = holders > 0

    fun acquire() { holders++ }
    fun release() { holders-- }
}

val LocalIdleResetGuard = compositionLocalOf { IdleResetGuard() }

// Keeps the idle reset from firing while this stays composed.
@Composable
fun SuppressIdleReset(active: Boolean = true) {
    val guard = LocalIdleResetGuard.current
    DisposableEffect(guard, active) {
        if (active) guard.acquire()
        onDispose { if (active) guard.release() }
    }
}

// Watches how long the app stayed in the background (screen off counts) and
// calls onIdle when it comes back after too long.
@Composable
fun IdleReturnToMenu(guard: IdleResetGuard, onIdle: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var leftAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> leftAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val away = if (leftAt == 0L) 0L else SystemClock.elapsedRealtime() - leftAt
                    leftAt = 0L
                    if (away > IDLE_TIMEOUT_MS && !guard.suppressed) onIdle()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
