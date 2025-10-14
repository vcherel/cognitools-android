package com.example.myapp.undercover

import androidx.compose.ui.graphics.Color
import kotlin.random.Random

// Player generation and role/word assignment
fun generateAndAssignPlayers(settings: GameSettings): List<Player> {
    val players = (0 until settings.playerCount).map { i ->
        Player(name = "Player ${i + 1}", role = PlayerRole.CIVILIAN, word = "")
    }.toMutableList()

    val wordPair = wordPairs.random()
    val (civilianWord, impostorWord) = if (Random.nextBoolean()) {
        wordPair.first to wordPair.second
    } else {
        wordPair.second to wordPair.first
    }

    val indices = players.indices.shuffled().toMutableList()

    var impostorCount = settings.impostorCount
    val mrWhiteCount = if (settings.randomComposition) {
        val maxMrWhite = settings.playerCount - 2
        var count = 0
        var chance = 0.5
        while (count < maxMrWhite && Random.nextDouble() < chance) {
            count++
            chance *= 0.5
        }
        count
    } else settings.mrWhiteCount

    if (settings.randomComposition) {
        val maxImpostors = ((settings.playerCount - mrWhiteCount) / 2) - 1
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

fun reassignRolesAndWords(players: List<Player>, settings: GameSettings): List<Player> {
    val newPlayers = generateAndAssignPlayers(settings)
    // Keep the original names
    return newPlayers.mapIndexed { index, p ->
        p.copy(name = players.getOrNull(index)?.name ?: p.name)
    }
}

// Game state utilities
fun List<Player>.eliminate(playerName: String): List<Player> {
    return map { if (it.name == playerName) it.copy(isEliminated = true) else it }
}

fun List<Player>.activePlayers() = filter { !it.isEliminated }

fun List<Player>.checkWinCondition(): WinCondition {
    val active = activePlayers()
    val civilians = active.count { it.role == PlayerRole.CIVILIAN }
    val impostors = active.count { it.role == PlayerRole.IMPOSTOR }
    val mrWhites = active.count { it.role == PlayerRole.MR_WHITE }

    return when {
        impostors == 0 && mrWhites == 0 -> WinCondition.CiviliansWin
        civilians <= 1 -> WinCondition.ImpostorsWin
        else -> WinCondition.Continue
    }
}

fun List<Player>.shouldMrWhiteGuess(): Boolean {
    val active = activePlayers()
    val civilians = active.count { it.role == PlayerRole.CIVILIAN }
    val impostors = active.count { it.role == PlayerRole.IMPOSTOR }
    val mrWhites = active.count { it.role == PlayerRole.MR_WHITE }

    return when {
        mrWhites == 0 -> false
        mrWhites > 0 && impostors > 0 && civilians == 0 -> true
        mrWhites > 0 && civilians == 0 && impostors == 0 -> true
        mrWhites > 0 && civilians == 1 && impostors == 0 -> true
        else -> false
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
