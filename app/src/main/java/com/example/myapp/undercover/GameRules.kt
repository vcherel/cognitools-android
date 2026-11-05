package com.example.myapp.undercover

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlin.random.Random

fun pickRandomPair(context: Context): Pair<String, String>? {
    var selectedPair: Pair<String, String>? = null
    var count = 0

    context.assets.open("pairs.json").bufferedReader().useLines { lines ->
        lines.forEach { line ->
            if (line.isNotBlank()) {
                count++
                if (Random.nextInt(count) == 0) {
                    val pairList: List<String> = Json.decodeFromString(line)
                    if (pairList.size == 2) {
                        selectedPair = pairList[0] to pairList[1]
                    }
                }
            }
        }
    }
    return selectedPair
}

private fun List<Player>.eliminate(playerName: String): List<Player> {
    return map { if (it.name == playerName) it.copy(isEliminated = true) else it }
}

fun List<Player>.activePlayers() = filter { !it.isEliminated }

fun List<Player>.getCivilianWord(): String =
    first { it.role == PlayerRole.CIVILIAN }.word

fun List<Player>.getImpostorWord(): String? =
    firstOrNull { it.role == PlayerRole.IMPOSTOR }?.word

// A Mr White still in play guesses once at most two players remain.
private fun List<Player>.shouldMrWhiteGuess(): Boolean {
    val active = activePlayers()
    return active.size <= 2 && active.any { it.role == PlayerRole.MR_WHITE }
}

// Checks civilian and impostor wins; a Mr White win is handled separately via his guess.
private fun List<Player>.checkWinCondition(): WinCondition {
    val active = activePlayers()
    val civilians = active.count { it.role == PlayerRole.CIVILIAN }
    val impostors = active.count { it.role == PlayerRole.IMPOSTOR }
    val mrWhites = active.count { it.role == PlayerRole.MR_WHITE }

    return when {
        impostors == 0 && mrWhites == 0 -> WinCondition.CiviliansWin
        impostors > 0 && civilians <= 1 -> WinCondition.ImpostorsWin
        else -> WinCondition.Continue
    }
}

private fun Map<String, Int>.updateScore(playerName: String, points: Int): Map<String, Int> {
    return toMutableMap().apply {
        this[playerName] = (this[playerName] ?: 0) + points
    }
}

private fun List<Player>.awardPoints(
    scores: Map<String, Int>,
    points: Int,
    winners: (Player) -> Boolean
): Map<String, Int> = filter(winners).fold(scores) { acc, player ->
    acc.updateScore(player.name, points)
}

fun handlePlayerElimination(
    state: UndercoverGameState,
    eliminatedPlayer: Player
): UndercoverGameState {
    val updatedPlayers = state.players.eliminate(eliminatedPlayer.name)
    val activePlayers = updatedPlayers.activePlayers()
    val activeCivilians = activePlayers.filter { it.role == PlayerRole.CIVILIAN }
    val activeMrWhites = activePlayers.filter { it.role == PlayerRole.MR_WHITE }

    return when {
        // Case 1: Mr. White was eliminated → They get to guess immediately
        eliminatedPlayer.role == PlayerRole.MR_WHITE -> {
            state.copy(
                players = updatedPlayers,
                gameState = GameState.MrWhiteGuess(
                    player = eliminatedPlayer,
                    correctWord = state.players.getCivilianWord(),
                    lastEliminated = eliminatedPlayer,
                    scenario = MrWhiteScenario.EliminatedMrWhite
                )
            )
        }

        // Case 2: Only Mr Whites left
        activeCivilians.isEmpty() && activeMrWhites.isNotEmpty() -> {
            val currentGuesser = activeMrWhites.first()
            state.copy(
                players = updatedPlayers,
                gameState = GameState.MrWhiteGuess(
                    player = currentGuesser,
                    correctWord = state.players.getCivilianWord(),
                    lastEliminated = eliminatedPlayer,
                    scenario = MrWhiteScenario.OnlyMrWhitesLeft(
                        activeMrWhites = activeMrWhites,
                        currentGuesser = currentGuesser
                    )
                )
            )
        }

        // Case 3: Final two (Mr White should guess)
        updatedPlayers.shouldMrWhiteGuess() -> {
            val mrWhiteToGuess = activeMrWhites.first()
            state.copy(
                players = updatedPlayers,
                gameState = GameState.MrWhiteGuess(
                    player = mrWhiteToGuess,
                    correctWord = state.players.getCivilianWord(),
                    lastEliminated = eliminatedPlayer,
                    scenario = MrWhiteScenario.FinalTwo(
                        mrWhite = mrWhiteToGuess,
                        opponent = activeCivilians.first()
                    )
                )
            )
        }

        // Case 4: Check normal win conditions
        else -> {
            handleWinCondition(
                condition = updatedPlayers.checkWinCondition(),
                players = updatedPlayers,
                scores = state.allPlayersScores,
                lastEliminated = eliminatedPlayer,
                skipEliminationScreen = false
            ).let { (newScores, newGameState) ->
                state.copy(
                    players = updatedPlayers,
                    allPlayersScores = newScores,
                    gameState = newGameState
                )
            }
        }
    }
}

fun handleMrWhiteGuess(
    state: UndercoverGameState,
    gameState: GameState.MrWhiteGuess,
    guessedWord: String
): UndercoverGameState {
    val updatedGuesses = state.mrWhiteGuesses + (gameState.player.name to guessedWord)
    val guessCorrect = guessedWord.equals(gameState.correctWord, ignoreCase = true)

    if (guessCorrect) {
        // Mr. White wins immediately
        val updatedScores = state.allPlayersScores.updateScore(
            gameState.player.name,
            ScoreValues.MR_WHITE_WIN
        )
        return state.copy(
            mrWhiteGuesses = updatedGuesses,
            allPlayersScores = updatedScores,
            gameState = GameState.GameOver(
                civiliansWon = false,
                lastEliminated = gameState.player
            )
        )
    }

    val updatedPlayers = if (gameState.player.isEliminated) {
        state.players
    } else {
        state.players.eliminate(gameState.player.name)
    }

    return if (updatedPlayers.shouldMrWhiteGuess()) {
        val active = updatedPlayers.activePlayers()
        val nextMrWhite = active.first { it.role == PlayerRole.MR_WHITE }
        val scenario = determineMrWhiteScenario(nextMrWhite, active)

        state.copy(
            mrWhiteGuesses = updatedGuesses,
            players = updatedPlayers,
            gameState = GameState.MrWhiteGuess(
                player = nextMrWhite,
                correctWord = gameState.correctWord,
                lastEliminated = gameState.lastEliminated,
                scenario = scenario
            )
        )
    } else {
        // No more Mr. Whites to guess, check win conditions
        handleWinCondition(
            condition = updatedPlayers.checkWinCondition(),
            players = updatedPlayers,
            scores = state.allPlayersScores,
            lastEliminated = gameState.player,
            skipEliminationScreen = true
        ).let { (newScores, newGameState) ->
            state.copy(
                mrWhiteGuesses = updatedGuesses,
                players = updatedPlayers,
                allPlayersScores = newScores,
                currentRound = state.currentRound + 1,
                gameState = newGameState
            )
        }
    }
}

private fun determineMrWhiteScenario(
    mrWhite: Player,
    activePlayers: List<Player>
): MrWhiteScenario {
    val activeCivilians = activePlayers.filter { it.role == PlayerRole.CIVILIAN }
    val activeMrWhites = activePlayers.filter { it.role == PlayerRole.MR_WHITE }

    return when {
        activeCivilians.isEmpty() && activePlayers.none { it.role == PlayerRole.IMPOSTOR } -> {
            MrWhiteScenario.OnlyMrWhitesLeft(
                activeMrWhites = activeMrWhites,
                currentGuesser = mrWhite
            )
        }
        else -> {
            MrWhiteScenario.FinalTwo(
                mrWhite = mrWhite,
                opponent = activeCivilians.first()
            )
        }
    }
}

/** The scores and the next game state once [condition] has been checked. */
private fun handleWinCondition(
    condition: WinCondition,
    players: List<Player>,
    scores: Map<String, Int>,
    lastEliminated: Player,
    skipEliminationScreen: Boolean
): Pair<Map<String, Int>, GameState> {
    return when (condition) {
        WinCondition.CiviliansWin -> {
            val updatedScores = players.awardPoints(scores, ScoreValues.CIVILIAN_WIN) {
                it.role == PlayerRole.CIVILIAN
            }
            updatedScores to GameState.GameOver(civiliansWon = true, lastEliminated = lastEliminated)
        }

        WinCondition.ImpostorsWin -> {
            val updatedScores = players.awardPoints(scores, ScoreValues.IMPOSTOR_WIN) {
                it.role != PlayerRole.CIVILIAN
            }
            updatedScores to GameState.GameOver(civiliansWon = false, lastEliminated = lastEliminated)
        }

        WinCondition.Continue -> {
            // A wrong guess from Mr. White goes straight to the next round, a vote shows its result first.
            scores to if (skipEliminationScreen) GameState.PlayMenu else GameState.EliminationResult(player = lastEliminated)
        }
    }
}

fun PlayerRole.displayName(): String = when (this) {
    PlayerRole.CIVILIAN -> "Civil"
    PlayerRole.IMPOSTOR -> "Undercover"
    PlayerRole.MR_WHITE -> "M. White"
}

fun PlayerRole.displayColor(): Color = when (this) {
    PlayerRole.CIVILIAN -> Color.Blue
    PlayerRole.IMPOSTOR -> Color(0xFFFF6600)
    PlayerRole.MR_WHITE -> Color.Red
}