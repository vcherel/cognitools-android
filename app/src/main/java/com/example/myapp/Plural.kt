package com.example.myapp

/** The "s" a noun takes past one, or nothing. The one place the count-to-plural rule lives. */
fun plural(count: Int): String = if (count > 1) "s" else ""
