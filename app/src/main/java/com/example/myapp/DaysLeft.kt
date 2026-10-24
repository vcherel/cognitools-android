package com.example.myapp

import java.util.concurrent.TimeUnit

/** Whole days until [expiresAtMillis], rounded up and at least 1: what a trash row says is left. */
fun daysLeftUntil(expiresAtMillis: Long): Int {
    val dayMillis = TimeUnit.DAYS.toMillis(1)
    val millisLeft = expiresAtMillis - System.currentTimeMillis()
    return ((millisLeft + dayMillis - 1) / dayMillis).toInt().coerceAtLeast(1)
}
