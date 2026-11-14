package com.dharmil.reconnector.navigation.store

internal object OfflineDownloadBudget {
    const val MAX_COMPLETED_TILES = 2_800L
    const val MAX_COMPLETED_BYTES = 32L * 1_000_000L

    fun exceeded(completedTiles: Long, completedBytes: Long): Boolean =
        completedTiles >= MAX_COMPLETED_TILES || completedBytes >= MAX_COMPLETED_BYTES
}
