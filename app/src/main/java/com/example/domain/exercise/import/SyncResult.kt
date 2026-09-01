package com.example.domain.exercise.import

data class SyncResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int
)
