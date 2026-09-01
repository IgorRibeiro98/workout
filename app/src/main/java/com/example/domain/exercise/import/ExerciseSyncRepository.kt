package com.example.domain.exercise.import

interface ExerciseSyncRepository {
    suspend fun synchronize(limit: Int = 100, offset: Int = 0): SyncResult
}
