package com.example.domain.social

/**
 * Gateway de fronteira para compartilhamento seguro de treinos entre amigos (T17.7).
 *
 * Operações são server-authoritative e idempotentes via clientRequestId.
 */
interface WorkoutShareGateway {
    val isConfigured: Boolean

    suspend fun createShare(
        recipientSocialId: String,
        clientRequestId: String,
        snapshot: SharedWorkoutSnapshot
    ): WorkoutShareOutcome<WorkoutShareDetail>

    suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>>

    suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>>

    suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail>

    suspend fun acceptShare(shareId: String): WorkoutShareOutcome<SharedWorkoutSnapshot>

    suspend fun completeImport(shareId: String): WorkoutShareOutcome<Unit>

    suspend fun declineShare(shareId: String): WorkoutShareOutcome<Unit>

    suspend fun cancelShare(shareId: String): WorkoutShareOutcome<Unit>
}
