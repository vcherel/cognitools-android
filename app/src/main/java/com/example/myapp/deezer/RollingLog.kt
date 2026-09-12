package com.example.myapp.deezer

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A capped text log for the jobs that run days apart (the offline sync, the discoveries batch).
 * Kept in getExternalFilesDir so a release build's log reads with plain adb (run-as only works on
 * debug builds):
 *   adb shell cat /sdcard/Android/data/com.example.myapp/files/<fileName>
 */
class RollingLog(
    context: Context,
    private val tag: String,
    fileName: String,
    private val maxBytes: Long,
    private val keepLines: Int
) {
    private val file: File by lazy { File(context.getExternalFilesDir(null) ?: context.filesDir, fileName) }

    /** Appends one line. Callers are already on IO. */
    fun log(line: String) {
        Log.i(tag, line)
        runCatching {
            file.appendText("${LocalDateTime.now().format(STAMP)} $line\n")
            if (file.length() > maxBytes) {
                val kept = file.readLines().takeLast(keepLines)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Flattens a throwable and its causes into one loggable line: the message is what identifies it. */
    fun describe(t: Throwable?): String =
        generateSequence(t) { it.cause }
            .take(3)
            .joinToString(", caused by ") { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }
            .ifBlank { "unknown error" }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
