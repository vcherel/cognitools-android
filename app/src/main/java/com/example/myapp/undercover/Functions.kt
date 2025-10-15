package com.example.myapp.undercover

import androidx.compose.ui.graphics.Color
import kotlin.random.Random

// Player generation and role assignment

// Player generation and role/word assignment
fun generateAndAssignPlayers(state: UndercoverGameState): List<Player> {
    val players = (0 until state.players.size).map { i ->
        Player(name = "Player ${i + 1}", role = PlayerRole.CIVILIAN, word = "")
    }.toMutableList()

    val wordPair = wordPairs.random()
    val (civilianWord, impostorWord) = if (Random.nextBoolean()) {
        wordPair.first to wordPair.second
    } else {
        wordPair.second to wordPair.first
    }

    val indices = players.indices.shuffled().toMutableList()

    var impostorCount = state.settings.impostorCount
    val mrWhiteCount = if (state.settings.randomComposition) {
        val maxMrWhite = state.players.size - 2
        var count = 0
        var chance = 0.5
        while (count < maxMrWhite && Random.nextDouble() < chance) {
            count++
            chance *= 0.5
        }
        count
    } else state.settings.mrWhiteCount

    if (state.settings.randomComposition) {
        val maxImpostors = ((state.players.size - mrWhiteCount) / 2) - 1
        impostorCount = if (maxImpostors >= 1) Random.nextInt(1, maxImpostors + 1) else 0

        // Guarantee at least one non-civilian
        if (impostorCount == 0 && mrWhiteCount == 0) { impostorCount = 1 }
    }

    var nextIndex = 0

    repeat(mrWhiteCount) {
        val idx = indices[nextIndex++]
        players[idx] = players[idx].copy(role = PlayerRole.MR_WHITE, word = "")
    }

    repeat(impostorCount) {
        val idx = indices[nextIndex++]
        players[idx] = players[idx].copy(role = PlayerRole.IMPOSTOR, word = impostorWord)
    }

    for (i in nextIndex until indices.size) {
        val idx = indices[i]
        players[idx] = players[idx].copy(role = PlayerRole.CIVILIAN, word = civilianWord)
    }

    return players
}

fun reassignRolesAndWords(state: UndercoverGameState): List<Player> {
    // When replaying game
    val newPlayers = generateAndAssignPlayers(state)
    // Keep the original names
    return newPlayers.mapIndexed { index, p ->
        p.copy(name = state.players.getOrNull(index)?.name ?: p.name)
    }
}

// Player list extensions
fun List<Player>.eliminate(playerName: String): List<Player> {
    return map { if (it.name == playerName) it.copy(isEliminated = true) else it }
}

fun List<Player>.activePlayers() = filter { !it.isEliminated }

fun List<Player>.getCivilianWord(): String =
    first { it.role == PlayerRole.CIVILIAN }.word

// Game logic helpers

fun List<Player>.shouldMrWhiteGuess(): Boolean {
    // Check if Mr. White should guess (other case than Mr White eliminated)
    val active = activePlayers()
    val mrWhites = active.count { it.role == PlayerRole.MR_WHITE }

    // If no Mr White is active, he shouldn't guess
    if (mrWhites == 0) return false

    // If there is only one or two active player, he should guess
    if (active.size <= 2) return true

    return false
}

fun List<Player>.checkWinCondition(): WinCondition {
    // We check win condition but not Mr White win
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

// Scoring utilities
fun Map<String, Int>.updateScore(playerName: String, points: Int): Map<String, Int> {
    return toMutableMap().apply {
        this[playerName] = (this[playerName] ?: 0) + points
    }
}

fun List<Player>.awardCivilianPoints(scores: Map<String, Int>): Map<String, Int> {
    var updatedScores = scores
    filter { it.role == PlayerRole.CIVILIAN }.forEach { player ->
        updatedScores = updatedScores.updateScore(player.name, ScoreValues.CIVILIAN_WIN)
    }
    return updatedScores
}

fun List<Player>.awardImpostorPoints(scores: Map<String, Int>): Map<String, Int> {
    var updatedScores = scores
    filter { it.role != PlayerRole.CIVILIAN }.forEach { player ->
        updatedScores = updatedScores.updateScore(player.name, ScoreValues.IMPOSTOR_WIN)
    }
    return updatedScores
}

// Game state handler
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
                    scenario = MrWhiteScenario.EliminatedMrWhite()
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
        updatedPlayers.shouldMrWhiteGuess() && activeMrWhites.isNotEmpty() -> {
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
            ).let { (newPlayers, newScores, newGameState) ->
                state.copy(
                    players = newPlayers,
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

    // Incorrect guess - eliminate if not already eliminated
    val updatedPlayers = if (gameState.player.isEliminated) {
        state.players
    } else {
        state.players.eliminate(gameState.player.name)
    }

    // Check if any remaining Mr. Whites need to guess
    val remainingMrWhites = updatedPlayers.activePlayers()
        .filter { it.role == PlayerRole.MR_WHITE }

    return if (remainingMrWhites.isNotEmpty() && updatedPlayers.shouldMrWhiteGuess()) {
        // Next Mr. White guesses
        val nextMrWhite = remainingMrWhites.first()
        val scenario = determineMrWhiteScenario(nextMrWhite, updatedPlayers.activePlayers())

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
        ).let { (newPlayers, newScores, newGameState) ->
            state.copy(
                mrWhiteGuesses = updatedGuesses,
                players = newPlayers,
                allPlayersScores = newScores,
                currentRound = state.currentRound + 1,
                gameState = newGameState
            )
        }
    }
}

fun determineMrWhiteScenario(
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

fun handleWinCondition(
    condition: WinCondition,
    players: List<Player>,
    scores: Map<String, Int>,
    lastEliminated: Player,
    skipEliminationScreen: Boolean = false
): Triple<List<Player>, Map<String, Int>, GameState> {
    return when (condition) {
        WinCondition.CiviliansWin -> {
            val updatedScores = players.awardCivilianPoints(scores)
            Triple(
                players,
                updatedScores,
                GameState.GameOver(civiliansWon = true, lastEliminated = lastEliminated)
            )
        }

        WinCondition.ImpostorsWin -> {
            val updatedScores = players.awardImpostorPoints(scores)
            Triple(
                players,
                updatedScores,
                GameState.GameOver(civiliansWon = false, lastEliminated = lastEliminated)
            )
        }

        WinCondition.Continue -> {
            if (skipEliminationScreen) {
                // After Mr. White's wrong guess → Go directly to next round
                Triple(
                    players,
                    scores,
                    GameState.PlayMenu
                )
            } else {
                // After voting elimination → Show elimination result screen first
                Triple(
                    players,
                    scores,
                    GameState.EliminationResult(player = lastEliminated, gameOver = false)
                )
            }
        }
    }
}

// Display utilities

fun PlayerRole.displayName(): String = when (this) {
    PlayerRole.CIVILIAN -> "Civilian"
    PlayerRole.IMPOSTOR -> "Impostor"
    PlayerRole.MR_WHITE -> "Mr. White"
}

fun PlayerRole.displayColor(): Color = when (this) {
    PlayerRole.CIVILIAN -> Color.Blue
    PlayerRole.IMPOSTOR -> Color(0xFFFF6600)
    PlayerRole.MR_WHITE -> Color.Red
}