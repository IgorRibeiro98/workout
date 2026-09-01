package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncCheckpointStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    SKIPPED,
    FAILED
}

@Entity(tableName = "exercise_sync_checkpoints")
data class ExerciseSyncCheckpointEntity(
    @PrimaryKey val exerciseId: Long,
    val exerciseName: String,
    val status: String = SyncCheckpointStatus.PENDING.name,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
