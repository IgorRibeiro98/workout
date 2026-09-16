package com.example.domain.multiplayer

/**
 * Como este aparelho está ligado à sala, agora (T19.5 §11).
 *
 * Estados de **conexão**, e não de treino: nenhum deles diz nada sobre a `WorkoutSession` local,
 * que continua `IN_PROGRESS` em qualquer um. A tela usa isto para dizer "reconectando" ou "sessão
 * em dupla perdida" sem nunca sugerir que o treino falhou.
 */
enum class MultiplayerConnection {
    /** Primeira leitura da sala ainda não voltou. */
    CONNECTING,
    CONNECTED,
    /** A última chamada falhou por rede/servidor; a próxima tentativa já está agendada. */
    RECONNECTING,
    /** A sala acabou para este aparelho. Ver [MultiplayerEndReason]. Não há reconexão. */
    ENDED
}

enum class MultiplayerEndReason {
    ROOM_CLOSED,
    ROOM_EXPIRED,
    ROOM_NOT_FOUND,
    /** Este aparelho saiu da sala por decisão explícita. */
    LEFT,
    /** O vínculo é de outra conta: depois de trocar de conta, a sala não é mais deste usuário. */
    OTHER_ACCOUNT,
    AUTH_REQUIRED,
    NOT_CONFIGURED,
    /** O servidor recusou algo que não é transitório (contrato, membership). */
    REJECTED
}

/** O outro participante, como a tela o mostra: quem é, se está aqui, e o que já fez. */
data class PeerView(
    val displayName: String,
    val status: MultiplayerMemberStatus,
    val connected: Boolean,
    val progress: PeerProgress
)

data class MultiplayerSessionState(
    val roomId: String,
    val role: MultiplayerMemberRole,
    val connection: MultiplayerConnection,
    val endReason: MultiplayerEndReason? = null,
    val room: MultiplayerRoom? = null,
    val peer: PeerView? = null,
    /** Eventos locais ainda não confirmados pelo servidor — "aguardando rede", nunca "perdidos". */
    val pendingLocalEvents: Int = 0,
    val lastSyncAt: Long? = null
) {
    val isLive: Boolean get() = connection == MultiplayerConnection.CONNECTED

    /** A sala está esperando o convidado entrar. */
    val isWaitingForPeer: Boolean
        get() = room?.status == MultiplayerRoomStatus.WAITING
}
