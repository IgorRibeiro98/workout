package com.example.domain.social

import kotlinx.serialization.Serializable

/**
 * Snapshot imutável e portável de uma rotina de treino compartilhada (T17.7).
 *
 * Contém apenas metadados estruturais e referências canônicas do catálogo.
 * Dados privados (cargas planejadas, anotações, histórico, máquinas, syncIds)
 * são estritamente excluídos deste modelo.
 */
@Serializable
data class SharedWorkoutSnapshot(
    val snapshotVersion: Int = 1,
    val name: String,
    val shortIdentifier: String? = null,
    val exercises: List<SharedExerciseSnapshot> = emptyList()
)

@Serializable
data class SharedExerciseSnapshot(
    val canonicalExerciseId: String,
    val sortOrder: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restDurationSeconds: Int
)

enum class WorkoutShareStatus {
    PENDING,
    ACCEPTED,
    IMPORTED,
    DECLINED,
    CANCELLED,
    EXPIRED
}

@Serializable
data class WorkoutShareOtherUser(
    val socialId: String,
    val displayName: String
)

data class WorkoutShareItem(
    val shareId: String,
    val status: WorkoutShareStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val templateName: String,
    val exerciseCount: Int,
    val otherUser: WorkoutShareOtherUser
)

data class WorkoutShareDetail(
    val shareId: String,
    val status: WorkoutShareStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val sender: WorkoutShareOtherUser,
    val recipient: WorkoutShareOtherUser,
    val snapshot: SharedWorkoutSnapshot? = null
)

sealed interface WorkoutShareOutcome<out T> {
    data class Success<T>(val data: T) : WorkoutShareOutcome<T>
    data class Failure(val error: WorkoutShareError) : WorkoutShareOutcome<Nothing>
}

enum class WorkoutShareError {
    NOT_CONFIGURED,
    AUTH_REQUIRED,
    NETWORK,
    UNAVAILABLE,
    RATE_LIMITED,
    SOCIAL_NOT_ENABLED,
    FRIENDSHIP_REQUIRED,
    CANNOT_SHARE_SELF,
    BLOCKED_USER,
    SHARE_NOT_FOUND,
    INVALID_STATE,
    INVALID_SNAPSHOT,
    REJECTED
}
