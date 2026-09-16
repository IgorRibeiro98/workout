package com.example.domain.social

/**
 * Gateway de fronteira para compartilhamento seguro de treinos e programas entre amigos
 * (T17.7 / T19.3).
 *
 * Operações são server-authoritative e idempotentes via clientRequestId.
 */
interface WorkoutShareGateway {
    val isConfigured: Boolean

    /** Cria a oferta com o conteúdo tipado — um treino ou um programa inteiro (T19.3). */
    suspend fun createShare(
        recipientSocialId: String,
        clientRequestId: String,
        content: WorkoutShareContent
    ): WorkoutShareOutcome<WorkoutShareDetail>

    suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>>

    suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>>

    suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail>

    /**
     * Aceita no servidor — que revalida bloqueio, cancelamento, expiração e amizade — e devolve a
     * oferta com o conteúdo sobre o qual a cópia local é construída. Idempotente: repetir devolve
     * o mesmo conteúdo enquanto a oferta estiver `ACCEPTED` ou `IMPORTED`.
     */
    suspend fun acceptShare(shareId: String): WorkoutShareOutcome<WorkoutShareDetail>

    suspend fun completeImport(shareId: String): WorkoutShareOutcome<Unit>

    suspend fun declineShare(shareId: String): WorkoutShareOutcome<Unit>

    suspend fun cancelShare(shareId: String): WorkoutShareOutcome<Unit>
}
