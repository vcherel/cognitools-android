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
    // 50% chance not to have a Mr. White
    val mrWhiteCount = if (settings.randomComposition) Random.nextInt(0, 2) else settings.mrWhiteCount

    // If composition is random, pick a random impostor count
    if (settings.randomComposition) {
        val maxImpostors = (settings.playerCount - mrWhiteCount) / 2
        impostorCount = Random.nextInt(1, maxImpostors + 1)
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