package com.example.domain.engine

data class SyncErrorItem(
    val exerciseId: Long,
    val exerciseName: String,
    val reason: String
)

sealed class SyncState {
    object Idle : SyncState()
    object Preparing : SyncState()

    data class Running(
        val current: Int,
        val total: Int,
        val exerciseName: String,
        val percentage: Int
    ) : SyncState()

    data class Success(
        val processed: Int,
        val updated: Int,
        val skipped: Int = 0,
        val failed: Int = 0,
        val errorDetails: List<SyncErrorItem> = emptyList()
    ) : SyncState()

    data class Error(
        val message: String
    ) : SyncState()
}
