package com.example.domain.engine

import android.util.Log
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSyncCheckpointEntity
import com.example.data.local.SyncCheckpointStatus
import com.example.data.local.WorkoutDao

class ExerciseSyncQueue(
    private val dao: WorkoutDao
) {
    suspend fun hasIncompleteSync(): Boolean {
        val pending = dao.getPendingSyncCheckpoints()
        val all = dao.getAllSyncCheckpoints()
        return pending.isNotEmpty() && pending.size < all.size
    }

    suspend fun prepareQueue(exercises: List<ExerciseEntity>, forceRestart: Boolean = false): List<ExerciseSyncCheckpointEntity> {
        if (forceRestart) {
            dao.clearSyncCheckpoints()
        }

        val existingCheckpoints = dao.getAllSyncCheckpoints().associateBy { it.exerciseId }

        if (existingCheckpoints.isEmpty() || forceRestart) {
            val newCheckpoints = exercises.map { ex ->
                val initialStatus = if (shouldSkipExercise(ex)) {
                    SyncCheckpointStatus.SKIPPED.name
                } else {
                    SyncCheckpointStatus.PENDING.name
                }

                ExerciseSyncCheckpointEntity(
                    exerciseId = ex.id,
                    exerciseName = ex.name,
                    status = initialStatus,
                    attempts = 0,
                    lastError = null,
                    updatedAt = System.currentTimeMillis()
                )
            }
            dao.insertSyncCheckpoints(newCheckpoints)
            return newCheckpoints
        } else {
            // Incremental sync update for new exercises not yet in checkpoint table
            val missing = exercises.filter { !existingCheckpoints.containsKey(it.id) }
            if (missing.isNotEmpty()) {
                val added = missing.map { ex ->
                    val initialStatus = if (shouldSkipExercise(ex)) {
                        SyncCheckpointStatus.SKIPPED.name
                    } else {
                        SyncCheckpointStatus.PENDING.name
                    }
                    ExerciseSyncCheckpointEntity(
                        exerciseId = ex.id,
                        exerciseName = ex.name,
                        status = initialStatus,
                        attempts = 0,
                        lastError = null,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                dao.insertSyncCheckpoints(added)
            }
            return dao.getAllSyncCheckpoints()
        }
    }

    fun shouldSkipExercise(exercise: ExerciseEntity): Boolean {
        // Priority 1: Custom photo override locally saved
        if (!exercise.customPhotoUri.isNullOrBlank()) {
            return true
        }

        // Priority 2: Verified remote GIF with MATCHED status already synced
        if (!exercise.externalExerciseId.isNullOrBlank() &&
            exercise.mappingStatus == ExerciseMatchStatus.MATCHED.name &&
            !exercise.gifUrl.isNullOrBlank() &&
            exercise.lastVerifiedAt != null &&
            (System.currentTimeMillis() - exercise.lastVerifiedAt < 30L * 24 * 3600 * 1000)) { // Valid 30 days
            return true
        }

        return false
    }

    suspend fun markProcessing(exerciseId: Long, exerciseName: String, attempts: Int) {
        val checkpoint = ExerciseSyncCheckpointEntity(
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            status = SyncCheckpointStatus.PROCESSING.name,
            attempts = attempts,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateSyncCheckpoint(checkpoint)
    }

    suspend fun markSuccess(exerciseId: Long, exerciseName: String) {
        val checkpoint = ExerciseSyncCheckpointEntity(
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            status = SyncCheckpointStatus.SUCCESS.name,
            attempts = 1,
            lastError = null,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateSyncCheckpoint(checkpoint)
    }

    suspend fun markSkipped(exerciseId: Long, exerciseName: String, reason: String? = "Já atualizado (Cache local)") {
        val checkpoint = ExerciseSyncCheckpointEntity(
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            status = SyncCheckpointStatus.SKIPPED.name,
            attempts = 0,
            lastError = reason,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateSyncCheckpoint(checkpoint)
    }

    suspend fun markFailed(exerciseId: Long, exerciseName: String, attempts: Int, errorReason: String) {
        val checkpoint = ExerciseSyncCheckpointEntity(
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            status = SyncCheckpointStatus.FAILED.name,
            attempts = attempts,
            lastError = errorReason,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateSyncCheckpoint(checkpoint)
        Log.e("ExerciseSyncQueue", "[SYNC ERROR] Exercise: $exerciseName (ID: $exerciseId) falhou: $errorReason")
    }

    suspend fun clearQueue() {
        dao.clearSyncCheckpoints()
    }
}
