package com.example.myapp.undercover

import kotlin.random.Random

// 1. Player generation
fun generatePlayers(settings: GameSettings): List<Player> {
    return (0 until settings.playerCount).map { i ->
        Player(
            name = "Player ${i + 1}",
            role = PlayerRole.CIVILIAN,
            word = ""
        )
    }
}

// 2. Role and word assignment
fun assignRolesAndWords(players: List<Player>, settings: GameSettings): List<Player> {
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

    val updatedPlayers = players.toMutableList()
    var nextIndex = 0

    repeat(mrWhiteCount) {
        val idx = indices[nextIndex++]
        updatedPlayers[idx] = updatedPlayers[idx].copy(role = PlayerRole.MR_WHITE, word = "")
    }

    repeat(impostorCount) {
        val idx = indices[nextIndex++]
        updatedPlayers[idx] = updatedPlayers[idx].copy(role = PlayerRole.IMPOSTOR, word = impostorWord)
    }

    for (i in nextIndex until indices.size) {
        val idx = indices[i]
        updatedPlayers[idx] = updatedPlayers[idx].copy(role = PlayerRole.CIVILIAN, word = civilianWord)
    }

    return updatedPlayers
}

// 3. Game state utilities
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

// 4. Scoring utilities
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
