package com.example.myapp

import java.io.File

/**
 * Writes [text] aside then renames over this file. The process is killed screen-off often enough
 * that a direct write leaves a truncated file, which every JSON state loader then reads as empty.
 */
fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        writeText(text)
        tmp.delete()
    }
}
