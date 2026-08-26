package com.example.myapp

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
    return when {
        e is UnknownHostException || e is ConnectException -> "Pas de connexion"
        e is SocketTimeoutException -> "Le serveur ne répond pas"
        e is HttpStatusException && e.code == 429 -> "Trop de requêtes, réessaie dans un instant"
        e is HttpStatusException && e.code in 500..599 -> "Le serveur est en panne"
        e is HttpStatusException -> "Erreur serveur (${e.code})"
        e is IOException -> "Problème de réseau"
        else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}
