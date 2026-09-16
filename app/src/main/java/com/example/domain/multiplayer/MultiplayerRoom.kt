package com.example.domain.multiplayer

/**
 * O modelo de domínio do treino em dupla à distância (T19.5), do ponto de vista do aparelho.
 *
 * ```text
 * MultiplayerRoom   ≠   WorkoutSession
 * ```
 *
 * A sala é o que o servidor coordena: quem está nela, quem é o host, quem está conectado, o que
 * aconteceu e em que ordem. Nada aqui é a execução de ninguém — a `WorkoutSession` deste aparelho
 * continua no Room, sob `WorkoutEngine`, exatamente como em solo. Quando a rede some, tudo daqui
 * degrada; nada de lá muda.
 *
 * Identidade na fronteira: membros são `socialId` + `displayName`. Nenhum Firebase UID, e-mail,
 * `friendCode`, `deviceId` ou `syncId` entra ou sai por estes tipos.
 */
enum class MultiplayerRoomStatus { WAITING, ACTIVE, CLOSED, EXPIRED }

enum class MultiplayerMemberRole { HOST, GUEST }

enum class MultiplayerMemberStatus { INVITED, ACTIVE, FINISHED, LEFT }

data class MultiplayerMember(
    val socialId: String,
    val displayName: String,
    val role: MultiplayerMemberRole,
    val status: MultiplayerMemberStatus,
    /** Derivado no servidor do último poll — nunca decidido aqui. */
    val connected: Boolean,
    val lastSeenAt: Long?,
    val joinedAt: Long?
)

/** A forma portável do treino da sala — a mesma do compartilhamento (T17.7). */
data class MultiplayerWorkoutBlueprint(
    val name: String,
    val shortIdentifier: String?,
    val exercises: List<MultiplayerBlueprintExercise>
)

data class MultiplayerBlueprintExercise(
    val canonicalExerciseId: String,
    val sortOrder: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restDurationSeconds: Int
)

data class MultiplayerRoom(
    val roomId: String,
    val status: MultiplayerRoomStatus,
    val closeReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val hostSocialId: String,
    val myRole: MultiplayerMemberRole,
    val myStatus: MultiplayerMemberStatus,
    val members: List<MultiplayerMember>,
    val workout: MultiplayerWorkoutBlueprint,
    /** A última sequence atribuída pelo servidor; 0 quando ainda não há evento. */
    val lastSequence: Long
) {
    val isOpen: Boolean get() = status == MultiplayerRoomStatus.WAITING || status == MultiplayerRoomStatus.ACTIVE

    val me: MultiplayerMember? get() = members.firstOrNull { it.role == myRole }

    /** O outro participante — o convidado para o host, o host para o convidado. `null` numa sala só com o host. */
    val peer: MultiplayerMember? get() = members.firstOrNull { it.role != myRole }
}

data class MultiplayerInvitation(
    val roomId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val hostSocialId: String,
    val hostDisplayName: String,
    val workoutName: String,
    val exerciseCount: Int
)

/** Os tipos de evento do log da sala. Os três primeiros nascem no aparelho; os outros, no servidor. */
enum class MultiplayerEventType {
    WORKOUT_STARTED,
    SET_COMPLETED,
    MEMBER_FINISHED,
    MEMBER_JOINED,
    MEMBER_LEFT,
    ROOM_CLOSED,

    /** Um tipo que este build não conhece: ignorado ao reduzir, nunca um erro. */
    UNKNOWN
}

/**
 * Um evento como o servidor o devolve: identidade estável (`eventId`), ordem determinística
 * (`sequence`, atribuída pelo servidor) e quem o produziu (`actorSocialId`).
 */
data class MultiplayerEvent(
    val eventId: String,
    val sequence: Long,
    val actorSocialId: String,
    val type: MultiplayerEventType,
    val payload: MultiplayerEventPayload,
    val createdAt: Long
)

/**
 * O que atravessa a rede em cada tipo. Sem peso, sem repetição, sem RPE, sem PR, sem XP — o
 * servidor recusa esses campos, e este modelo não tem onde guardá-los.
 */
sealed interface MultiplayerEventPayload {
    data class WorkoutStarted(val exerciseCount: Int) : MultiplayerEventPayload

    data class SetCompleted(
        val canonicalExerciseId: String?,
        /** Posição do exercício na execução de quem publicou, 1-based. */
        val exercisePosition: Int,
        val setNumber: Int,
        val setCount: Int,
        /** Relógio do aparelho de quem publicou; informativo, nunca ordena nada. */
        val completedAt: Long
    ) : MultiplayerEventPayload

    data class RoomClosed(val reason: String?) : MultiplayerEventPayload

    data object Empty : MultiplayerEventPayload
}

/** O que o aparelho publica. `eventId` é determinístico por (sala, fato): reenviar é dedupe. */
data class OutgoingMultiplayerEvent(
    val eventId: String,
    val type: MultiplayerEventType,
    val payload: MultiplayerEventPayload
)

data class MultiplayerEventsPage(
    val room: MultiplayerRoom,
    val events: List<MultiplayerEvent>,
    val cursor: Long,
    val hasMore: Boolean
)

data class MultiplayerPublishReceipt(
    val accepted: List<Pair<String, Long>>,
    val room: MultiplayerRoom
)
