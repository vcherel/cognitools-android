package com.example.myapp

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs [send] until it succeeds, waiting longer after each failure (15 s doubling up to 10 min), so a
 * mailbox upload that failed offline goes out once the phone has internet again. Cancelling the
 * caller, as a newer change does, stops it.
 */
suspend fun retryUntilSent(send: suspend () -> Unit) {
    var wait = 15_000L
    while (true) {
        try {
            send()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            delay(wait)
            wait = (wait * 2).coerceAtMost(600_000L)
        }
    }
}

/**
 * How long a send on the way to the background waits first, so the note editor's 600 ms autosave
 * has written the last words before the snapshot goes.
 */
const val BACKGROUND_SEND_DELAY_MS = 1_000L
