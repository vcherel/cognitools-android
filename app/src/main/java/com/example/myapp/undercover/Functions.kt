package com.example.myapp.undercover

import kotlin.random.Random

fun generatePlayers(settings: GameSettings): List<Player> {
    return (0 until settings.playerCount).map { i ->
        Player(
            name = "Player ${i + 1}",
            role = PlayerRole.CIVILIAN,
            word = ""
        )
    }
}

fun assignRolesAndWords(players: List<Player>, settings: GameSettings): List<Player> {
    val wordPair = wordPairs.random()
    val (civilianWord, impostorWord) = if (Random.nextBoolean()) {
        wordPair.first to wordPair.second
    } else {
        wordPair.second to wordPair.first
    }

    // Random order of players
    val indices = players.indices.shuffled().toMutableList()

    // Determine number of impostors and Mr. Whites
    var impostorCount = settings.impostorCount


    val mrWhiteCount = if (settings.randomComposition) {
        val maxMrWhite = settings.playerCount - 2
        var count = 0
        var chance = 0.5
        while (count < maxMrWhite && Random.nextDouble() < chance) {
            count++
            chance *= 0.5 //
        }
        count
    } else settings.mrWhiteCount

    // If composition is random, pick a random impostor count
    if (settings.randomComposition) {
        val maxImpostors = ((settings.playerCount - mrWhiteCount) / 2) - 1
        impostorCount = if (maxImpostors >= 1) {
            Random.nextInt(1, maxImpostors + 1)
        } else {
            0 // It means there is at least 1 Mr White
        }
    }

    val updatedPlayers = players.toMutableList()
    var nextIndex = 0

    // Assign Mr. White
    repeat(mrWhiteCount) {
        val idx = indices[nextIndex++]
        updatedPlayers[idx] = updatedPlayers[idx].copy(role = PlayerRole.MR_WHITE, word = "")
    }

    // Assign Impostors
    repeat(impostorCount) {
        val idx = indices[nextIndex++]
        updatedPlayers[idx] = updatedPlayers[idx].copy(role = PlayerRole.IMPOSTOR, word = impostorWord)
    }

    // Assign Civilians
    for (i in nextIndex until indices.size) {
        val idx = indices[i]
        updatedPlayers[idx] = updatedPlayers[idx].copy(role = PlayerRole.CIVILIAN, word = civilianWord)
    }

    return updatedPlayers
}

fun Map<String, Int>.updateScore(playerName: String, points: Int): Map<String, Int> {
    return toMutableMap().apply {
        this[playerName] = (this[playerName] ?: 0) + points
    }
}

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

fun List<Player>.shouldMrWhiteGuess(): Boolean {
    val active = activePlayers()
    val civilians = active.count { it.role == PlayerRole.CIVILIAN }
    val impostors = active.count { it.role == PlayerRole.IMPOSTOR }
    val mrWhites = active.count { it.role == PlayerRole.MR_WHITE }

    return when {
        // No Mr. White alive
        mrWhites == 0 -> false
        // Only Mr. White(s) and Impostor(s) remain (no civilians)
        mrWhites > 0 && impostors > 0 && civilians == 0 -> true
        // Only Mr. White(s) remain (no civilians, no impostors)
        mrWhites > 0 && civilians == 0 && impostors == 0 -> true
        // Only Mr. White(s) and one Civilian remain (no impostors)
        mrWhites > 0 && civilians == 1 && impostors == 0 -> true
        else -> false
    }
}