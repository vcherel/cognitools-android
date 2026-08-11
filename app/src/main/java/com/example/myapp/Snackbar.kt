package com.example.myapp

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App wide snackbar, hosted once by the nav host in MainActivity. Screens post through it instead
 * of hosting their own, so an undo action outlives the screen that triggered it: deleting from the
 * gallery viewer closes the viewer, and the "Annuler" action still works from the album grid.
 */
object AppSnackbar {
    data class Request(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (suspend () -> Unit)? = null
    )

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests

    fun show(message: String, actionLabel: String? = null, onAction: (suspend () -> Unit)? = null) {
        _requests.tryEmit(Request(message, actionLabel, onAction))
    }
}

/**
 * A SnackbarHostState already wired to AppSnackbar's requests. Whoever draws the SnackbarHost owns
 * one of these: the nav host in the normal app, and the locked quick view, which runs outside it.
 */
@Composable
fun rememberAppSnackbarHostState(): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) {
        AppSnackbar.requests.collect { request ->
            val result = hostState.showSnackbar(
                message = request.message,
                actionLabel = request.actionLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) request.onAction?.invoke()
        }
    }
    return hostState
}
