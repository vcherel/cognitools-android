package com.example.myapp.undercover

// Player-related data
data class Player(
    val name: String,
    val role: PlayerRole = PlayerRole.CIVILIAN,
    val word: String = "",
    val isEliminated: Boolean = false,
    val points: Int = 0
)

enum class PlayerRole {
    CIVILIAN, IMPOSTOR, MR_WHITE
}

// Game settings
data class GameSettings(
    val impostorCount: Int = 1,
    val mrWhiteCount: Int = 0,
    val randomComposition: Boolean = true,
    val impostorsKnowRole: Boolean = false
)

// Word pairs
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

// Game state representations
sealed class GameState {
    object Settings : GameState()
    data class PlayerSetup(val playerIndex: Int, val showWord: Boolean) : GameState()
    object PlayMenu : GameState()
    object Voting : GameState()
    data class EliminationResult(val player: Player, val gameOver: Boolean) : GameState()
    data class MrWhiteGuess(val player: Player, val correctWord: String, val lastEliminated: Player, val mrWhiteGuesses: List<String> = emptyList(), val scenario: MrWhiteScenario) : GameState()
    data class GameOver(val civiliansWon: Boolean, val lastEliminated: Player, val mrWhiteGuesses: List<String> = emptyList()) : GameState()
    object Leaderboard : GameState()
}

sealed class MrWhiteScenario {
    data class EliminatedMrWhite(@Suppress("UNUSED_PARAMETER") val unused: Any? = null) : MrWhiteScenario() // No parameter but no warning
    data class FinalTwo(val mrWhite: Player, val opponent: Player) : MrWhiteScenario()
    data class OnlyMrWhitesLeft(val activeMrWhites: List<Player>, val currentGuesser: Player) : MrWhiteScenario()
}

data class UndercoverGameState(
    val gameState: GameState = GameState.Settings, // Game starts at settings page
    val settings: GameSettings = GameSettings(),
    val players: List<Player> = List(3) { index ->
        Player(name = "Player ${index + 1}")
    },
    val currentPlayerIndex: Int = 0,
    val currentRound: Int = 1,
    val allPlayersScores: Map<String, Int> = emptyMap(),
    val quickStart: Boolean = false
)

// Win condition and scoring
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
