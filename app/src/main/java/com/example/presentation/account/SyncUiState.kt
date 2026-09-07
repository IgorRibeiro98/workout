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
     * Desde a T16.7 a decisão existe de verdade: a seção lista os itens e oferece as escolhas que
     * fazem sentido para cada um. Nada é resolvido sozinho enquanto ninguém escolhe.
     */
    data class NeedsAttention(val items: Int, val lastSyncedAt: Long?) : SyncPhase

    /**
     * A posição deste aparelho no histórico do servidor não pode mais ser retomada (T16.7).
     *
     * Acontece quando o servidor já não guarda as mudanças que faltavam — na prática, quando o
     * banco dele voltou de uma cópia mais nova que a deste aparelho. Continuar andando pularia
     * mudanças, possivelmente exclusões, e ressuscitaria dado apagado.
     *
     * A tela **orienta** e não age: reconstruir a sincronização é uma operação com consequências,
     * e ela é do usuário. Nada é apagado, nada é restaurado automaticamente.
     */
    data class NeedsRebaseline(val lastSyncedAt: Long?) : SyncPhase

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
 * Por que uma resolução de conflito não pôde ser aplicada (T16.7).
 *
 * Só os casos em que o usuário pode fazer alguma coisa. "Já resolvido" não está aqui porque não é
 * falha: um segundo toque encontra a decisão já tomada, e a tela simplesmente mostra o resultado.
 */
enum class SyncResolutionProblem {

    /** A versão da nuvem ainda não chegou a este aparelho. Sincronize e tente de novo. */
    NO_REMOTE_COPY,

    /** Outra coisa ainda usa este item; apagá-lo deixaria uma referência quebrada. */
    STILL_REFERENCED,

    /** Há alterações dentro deste item que seriam perdidas junto. */
    PENDING_CHILD_CHANGES,

    /** A conta conectada mudou desde que a tela abriu. */
    ACCOUNT_MISMATCH,

    /** Não deu para aplicar a decisão. Nada foi alterado. */
    FAILED
}

/**
 * O que a tela de sincronização precisa saber.
 *
 * `deferredDeletes` conta as exclusões que **não viajam** por política do agregado — hoje nenhuma
 * chega a existir pelo caminho do app. Ele continua aqui porque uma limitação de convergência
 * precisa ser visível quando existir: esconder faria o usuário confiar em algo que não acontece.
 */
data class SyncUiState(
    val phase: SyncPhase = SyncPhase.NotConfigured,
    val pending: Int = 0,
    val needsAttention: Int = 0,
    val deferredDeletes: Int = 0,
    val lastSyncedAt: Long? = null,
    /**
     * Os itens que precisam de decisão, já traduzidos (T16.7).
     *
     * A lista chega pronta da camada de dados: título, as diferenças que importam e as escolhas
     * que fazem sentido. A tela **não** interpreta conflito — ela mostra e devolve o toque.
     */
    val conflicts: List<com.example.data.sync.SyncConflictSummary> = emptyList(),
    /** O conflito cuja resolução está sendo aplicada agora. Evita duplo toque. */
    val resolving: com.example.data.sync.SyncConflictId? = null,
    /** Por que a última resolução não pôde ser aplicada, quando foi o caso. */
    val resolutionProblem: SyncResolutionProblem? = null
) {

    /** Enquanto um ciclo roda, toques novos são ignorados. */
    val isBusy: Boolean get() = phase is SyncPhase.Syncing || resolving != null
}
