package com.example.myapp

import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a failed background job should say on screen. Two things it settles, both of which used to
 * leak raw text into the UI: a cancellation is not an error at all (leaving a screen mid-request
 * cancels its coroutine, and `runCatching` happily catches that, which is where "the coroutine
 * scope left the composition" came from), so it is rethrown; and a network failure gets a sentence
 * instead of the exception's own wording.
 */
fun userMessage(e: Throwable, fallback: String = "Une erreur est survenue"): String {
    if (e is CancellationException) throw e
    // Media3 wraps the socket failure of a stream or a download in its own IOException.
    val cause = if (e is HttpDataSource.HttpDataSourceException && e.cause != null) e.cause!! else e
    return when {
        e is HttpDataSource.InvalidResponseCodeException -> "Erreur serveur (${e.responseCode})"
        cause is UnknownHostException || cause is ConnectException -> "Pas de connexion"
        cause is SocketTimeoutException -> "Le serveur ne répond pas"
        e is HttpStatusException && e.code == 429 -> "Trop de requêtes, réessaie dans un instant"
        e is HttpStatusException && e.code in 500..599 -> "Le serveur est en panne"
        e is HttpStatusException -> "Erreur serveur (${e.code})"
        e is IOException -> "Problème de réseau"
        else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}

/**
 * `runCatching` for a job whose failure is simply ignored, minus the trap above: a cancellation
 * is rethrown, so an effect left mid-request stops instead of carrying on to write state into a
 * composition that is gone.
 */
inline fun <T> runIgnoringErrors(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
