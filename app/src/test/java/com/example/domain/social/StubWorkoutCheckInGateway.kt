package com.example.domain.social

/**
 * Um [WorkoutCheckInGateway] que recusa tudo, para os testes sobrescreverem o que lhes interessa.
 *
 * ## Por que ele existe
 *
 * A T17.9 acrescentou nove métodos à fronteira — mídia, reações, comentários, denúncia. Sem uma
 * base, cada dublê de teste precisaria declarar os nove, inclusive os que aquele teste não usa, e
 * o próximo método faria três arquivos de teste quebrarem por um motivo que não tem nada a ver com
 * o que eles afirmam.
 *
 * ## Por que o padrão é recusar, e não devolver sucesso vazio
 *
 * Porque um teste que chama sem querer um método que não configurou precisa **falhar de forma
 * visível**. `UNAVAILABLE` chega à ViewModel como indisponibilidade e aparece no estado; um
 * `Success(emptyList())` silencioso passaria despercebido e o teste afirmaria ter exercitado um
 * caminho que ele nunca tocou.
 */
open class StubWorkoutCheckInGateway : WorkoutCheckInGateway {

    override var isConfigured: Boolean = true

    override suspend fun uploadMedia(
        sessionSyncId: String,
        clientUploadId: String,
        bytes: ByteArray
    ): WorkoutCheckInOutcome<UploadedCheckInMedia> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun mediaBytes(mediaId: String): WorkoutCheckInOutcome<ByteArray> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun createCheckIn(
        sessionSyncId: String,
        clientRequestId: String,
        caption: String?,
        mediaId: String?
    ): WorkoutCheckInOutcome<WorkoutCheckIn> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun feed(limit: Int?): WorkoutCheckInOutcome<List<WorkoutCheckIn>> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun checkIn(checkInId: String): WorkoutCheckInOutcome<WorkoutCheckIn> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun deleteCheckIn(checkInId: String): WorkoutCheckInOutcome<Unit> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun putReaction(
        checkInId: String,
        type: ReactionType
    ): WorkoutCheckInOutcome<WorkoutCheckIn> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun removeReaction(
        checkInId: String
    ): WorkoutCheckInOutcome<WorkoutCheckIn> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun comments(
        checkInId: String,
        limit: Int?
    ): WorkoutCheckInOutcome<List<CheckInComment>> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun createComment(
        checkInId: String,
        body: String
    ): WorkoutCheckInOutcome<CheckInComment> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun deleteComment(
        checkInId: String,
        commentId: String
    ): WorkoutCheckInOutcome<Unit> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

    override suspend fun reportContent(
        target: SocialReportTarget,
        targetId: String,
        reason: String
    ): WorkoutCheckInOutcome<Unit> =
        WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
}
