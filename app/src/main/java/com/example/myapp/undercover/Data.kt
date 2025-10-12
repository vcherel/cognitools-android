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

sealed class GameState {
    object Settings : GameState()
    data class PlayerSetup(val playerIndex: Int, val showWord: Boolean) : GameState()
    object RoundMenu : GameState()
    object Voting : GameState()
    data class EliminationResult(val player: Player, val gameOver: Boolean) : GameState()
    data class MrWhiteGuess(val player: Player, val correctWord: String) : GameState()
    data class GameOver(val civiliansWon: Boolean, val lastEliminated: Player) : GameState()
}

data class UndercoverGameState(
    val gameState: GameState = GameState.Settings,
    val settings: GameSettings = GameSettings(),
    val players: List<Player> = emptyList(),
    val currentPlayerIndex: Int = 0,
    val currentRound: Int = 1,
    val allPlayersScores: Map<String, Int> = emptyMap()
)

// Win condition enum
enum class WinCondition {
    CiviliansWin,
    ImpostorsWin,
    Continue
}

object ScoreValues {
    const val CIVILIAN_WIN = 1
    const val IMPOSTOR_WIN = 2
    const val MR_WHITE_WIN = 3
}
