package com.example.myapp.undercover

import androidx.compose.ui.graphics.Color
import kotlin.random.Random

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
        impostorCount = if (maxImpostors >= 1) {
            Random.nextInt(1, maxImpostors + 1)
        } else 0
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

// Game state utilities
fun List<Player>.eliminate(playerName: String): List<Player> {
    return map { if (it.name == playerName) it.copy(isEliminated = true) else it }
}

fun List<Player>.activePlayers() = filter { !it.isEliminated }

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
