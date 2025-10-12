package com.example.myapp.undercover

data class Player(
    val name: String,
    val role: PlayerRole,
    val word: String,
    val isEliminated: Boolean = false,
    val points: Int = 0
)

enum class PlayerRole {
    CIVILIAN, IMPOSTOR, MR_WHITE
}

data class GameSettings(
    val playerCount: Int = 4,
    val impostorCount: Int = 1,
    val mrWhiteCount: Int = 0,
    val randomComposition: Boolean = true,
    val impostorsKnowRole: Boolean = false
)

val wordPairs = listOf(
    "Dog" to "Cat",
    "Coffee" to "Tea",
    "Pizza" to "Burger",
    "Car" to "Bike",
    "Summer" to "Winter",
    "Ocean" to "Lake",
    "Book" to "Magazine",
    "Guitar" to "Piano",
    "Apple" to "Orange",
    "Football" to "Basketball"
)
