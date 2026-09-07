package com.example.presentation.account

/**
 * O estado da sincronização na tela (T16.6).
 *
 * ## Por que não um `isLoading`
 *
 * "Sem internet", "esta conta não é a dona destes dados" e "1 item precisa de atenção" pedem
 * ações diferentes do usuário. Um booleano colapsaria os três em "não deu" e a tela teria que
 * adivinhar qual — o mesmo motivo pelo qual o backup e o restore têm fases próprias.
 *
 * ## O que esta tela nunca mostra
 *
 * `cursor=1842`, `revision mismatch`, `baseRevision`, `serverSequence` e `syncId` são vocabulário
 * de protocolo. Eles existem no banco e no log técnico; na Conta Spark o usuário lê "Atualizado
 * agora", "3 alterações aguardando conexão" ou "1 item precisa de atenção".
 */
sealed interface SyncPhase {

    /** Não há endereço de Spark Backend neste build. A seção não aparece. */
    data object NotConfigured : SyncPhase

    /**
     * O conjunto de dados deste aparelho não pertence a nenhuma Conta Spark.
     *
     * Entrar na conta **não** liga o sync: só um dataset adotado (backup) ou restaurado
     * participa. A tela explica isso em vez de oferecer um botão que não faria nada.
     */
    data object Disabled : SyncPhase

    /** O dataset tem dono e não há sessão conectada agora. */
    data object AuthRequired : SyncPhase

    /** Os dados deste aparelho pertencem a outra Conta Spark. Só a nuvem fica indisponível. */
    data object AccountMismatch : SyncPhase

    /** Um ciclo está rodando. */
    data object Syncing : SyncPhase

    /** Tudo que este aparelho tinha para enviar subiu, e ele está em dia com o servidor. */
    data class UpToDate(val lastSyncedAt: Long?) : SyncPhase

    /** Há alterações locais esperando conexão — ou um ciclo que ainda não rodou. */
    data class Pending(val pending: Int, val lastSyncedAt: Long?) : SyncPhase

    /** Sem rede. O Spark continua completo; a fila continua guardada. */
    data class Offline(val pending: Int, val lastSyncedAt: Long?) : SyncPhase

    /**
     * Há itens que precisam de decisão do usuário.
     *
     * A T16.6 **detecta e preserva**; quem resolve é a T16.7. A tela diz que existe algo a
     * resolver e não promete uma tela de resolução que ainda não existe.
     */
    data class NeedsAttention(val items: Int, val lastSyncedAt: Long?) : SyncPhase

    /** O ciclo terminou com erro recuperável. [reason] é classe de falha, nunca código interno. */
    data class Failed(val reason: SyncFailure, val lastSyncedAt: Long?) : SyncPhase
}

/**
 * Por que o ciclo não completou.
 *
 * Classes de falha, e não mensagens do servidor: mensagem de servidor não é texto de UI, e um
 * `code` interno não ajuda quem está olhando a tela.
 */
enum class SyncFailure {
    /** O Spark Backend respondeu indisponível. Recuperável. */
    UNAVAILABLE,

    /** O servidor pediu para diminuir o ritmo. Recuperável sozinho. */
    RATE_LIMITED,

    /** O servidor recusou a requisição. Isso é defeito, e o texto convida a relatar. */
    REJECTED,

    /**
     * Este aplicativo não sabe ler alguma alteração que veio de outro aparelho.
     *
     * O sync pausa exatamente ali, sem perder nada, e volta a andar quando o app for atualizado.
     */
    NEEDS_APP_UPDATE
}

/**
 * O que a tela de sincronização precisa saber.
 *
 * `deferredDeletes` existe porque a limitação precisa ser **visível**: exclusões feitas neste
 * aparelho ainda não chegam aos outros (T16.7). Esconder isso faria o usuário confiar em uma
 * convergência que não acontece.
 */
data class SyncUiState(
    val phase: SyncPhase = SyncPhase.NotConfigured,
    val pending: Int = 0,
    val needsAttention: Int = 0,
    val deferredDeletes: Int = 0,
    val lastSyncedAt: Long? = null
) {

    /** Enquanto um ciclo roda, toques novos são ignorados. */
    val isBusy: Boolean get() = phase is SyncPhase.Syncing
}
