package com.example.myapp

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
