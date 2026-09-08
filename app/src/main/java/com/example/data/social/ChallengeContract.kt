package com.example.data.social

/**
 * O contrato dos desafios, do lado do Android (T17.3).
 *
 * O espelho TypeScript é `backend/src/modules/social/challenge.contract.ts`, e a descrição legível
 * está em `docs/architecture/challenge-domain.md`. Mudou de um lado, muda do outro — e as duas
 * versões precisam do mesmo commit.
 *
 * ## O que o app envia, e o que ele nunca envia
 *
 * ```text
 * ENVIA    clientRequestId, name, type, target, startDate, endDate, timeZoneId,
 *          invitedSocialIds  — e a resposta a um convite
 *
 * NUNCA    score, progress, rank, winner, goalReached, creatorUid, participantUids,
 *          status, startsAt, endsAtExclusive
 * ```
 *
 * A segunda lista não é disciplina deste arquivo: o servidor **recusa a requisição inteira** se
 * qualquer um daqueles campos aparecer no corpo. O app não tem como afirmar a própria pontuação, e
 * é assim que "o Igor está com 8 de 12" continua significando alguma coisa.
 */
object ChallengeContract {

    /** `POST` — cria e convida. `GET` — os desafios de que participo. */
    const val CHALLENGES_PATH = "v1/social/challenges"

    /** `GET` — os convites que recebi. */
    const val INVITATIONS_PATH = "v1/social/challenge-invitations"

    /** `GET` — um desafio e o placar dele. Exige participação; quem verifica é o servidor. */
    fun challengePath(challengeId: String): String =
        "$CHALLENGES_PATH/${encode(challengeId)}"

    /** `POST` — só quem criou. */
    fun cancelPath(challengeId: String): String =
        "$CHALLENGES_PATH/${encode(challengeId)}/cancel"

    /** `POST` — só membro. */
    fun leavePath(challengeId: String): String =
        "$CHALLENGES_PATH/${encode(challengeId)}/leave"

    /** `POST` — só o destinatário. */
    fun acceptPath(invitationId: String): String =
        "$INVITATIONS_PATH/${encode(invitationId)}/accept"

    /** `POST` — só o destinatário. */
    fun declinePath(invitationId: String): String =
        "$INVITATIONS_PATH/${encode(invitationId)}/decline"

    /** `?cursor=` — a continuação opaca que o servidor devolveu, reenviada como veio. */
    fun withCursor(path: String, cursor: String?): String =
        if (cursor.isNullOrBlank()) path else "$path?cursor=${encode(cursor)}"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** Os códigos do envelope de erro do servidor que o app traduz. */
    object ErrorCodes {
        const val INVALID_CHALLENGE_REQUEST = "INVALID_CHALLENGE_REQUEST"
        const val INVALID_CHALLENGE_TYPE = "INVALID_CHALLENGE_TYPE"
        const val INVALID_CHALLENGE_TARGET = "INVALID_CHALLENGE_TARGET"
        const val INVALID_CHALLENGE_PERIOD = "INVALID_CHALLENGE_PERIOD"
        const val INVALID_CHALLENGE_TIMEZONE = "INVALID_CHALLENGE_TIMEZONE"
        const val TOO_MANY_PARTICIPANTS = "TOO_MANY_PARTICIPANTS"
        const val CHALLENGE_PARTICIPANT_NOT_AVAILABLE = "CHALLENGE_PARTICIPANT_NOT_AVAILABLE"
        const val TOO_MANY_OPEN_CHALLENGES = "TOO_MANY_OPEN_CHALLENGES"
        const val CHALLENGE_NOT_FOUND = "CHALLENGE_NOT_FOUND"
        const val CHALLENGE_INVITATION_NOT_FOUND = "CHALLENGE_INVITATION_NOT_FOUND"
        const val CHALLENGE_INVITATION_NOT_PENDING = "CHALLENGE_INVITATION_NOT_PENDING"
        const val CHALLENGE_ALREADY_STARTED = "CHALLENGE_ALREADY_STARTED"
        const val CHALLENGE_CANCELLED = "CHALLENGE_CANCELLED"
        const val NOT_CHALLENGE_CREATOR = "NOT_CHALLENGE_CREATOR"
        const val CANNOT_LEAVE_AS_CREATOR = "CANNOT_LEAVE_AS_CREATOR"
        const val CHALLENGE_IDEMPOTENCY_CONFLICT = "CHALLENGE_IDEMPOTENCY_CONFLICT"
        const val CHALLENGE_RATE_LIMITED = "CHALLENGE_RATE_LIMITED"
    }

    /**
     * Os limites que a UI respeita para não oferecer o que será recusado.
     *
     * Eles são **cópia** do que o servidor aplica (`challenge.limits.ts`), e não a autoridade: um
     * APK desatualizado não amplia nenhum deles, e o servidor recusa igual. Eles existem para que
     * a tela dê o retorno antes do toque, e não para decidir.
     */
    object Limits {
        const val NAME_MIN_LENGTH = 3
        const val NAME_MAX_LENGTH = 60
        const val MIN_DURATION_DAYS = 1
        const val MAX_DURATION_DAYS = 90
        const val MIN_TARGET = 1
        const val MAX_WORKOUTS_TARGET = 200

        /** Incluindo quem cria. A tela oferece no máximo `MAX_PARTICIPANTS - 1` convites. */
        const val MAX_PARTICIPANTS = 10
    }
}
