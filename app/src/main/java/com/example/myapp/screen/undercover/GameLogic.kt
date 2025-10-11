package com.example.myapp.screen.undercover

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

fun assignRolesAndWords(players: List<Player>, settings: GameSettings) {
    val wordPair = wordPairs.random()
    val civilianWord = wordPair.first
    val impostorWord = wordPair.second

    val indices = players.indices.toMutableList()
    indices.shuffle()

    var impostorCount = settings.impostorCount
    val mrWhiteCount = settings.mrWhiteCount

    if (settings.randomComposition) {
        val maxImpostors = (settings.playerCount - mrWhiteCount) / 2
        impostorCount = Random.nextInt(1, maxImpostors + 1)
    }

    var assigned = 0

    // Assign Mr. White
    repeat(mrWhiteCount) {
        if (assigned < indices.size) {
            val idx = indices[assigned]
            players[idx].role = PlayerRole.MR_WHITE
            players[idx].word = ""
            assigned++
        }
    }

    // Assign Impostors
    repeat(impostorCount ?: 0) {
        if (assigned < indices.size) {
            val idx = indices[assigned]
            players[idx].role = PlayerRole.IMPOSTOR
            players[idx].word = impostorWord
            assigned++
        }
    }

    // Assign Civilians
    for (i in assigned until indices.size) {
        val idx = indices[i]
        players[idx].role = PlayerRole.CIVILIAN
        players[idx].word = civilianWord
    }
}