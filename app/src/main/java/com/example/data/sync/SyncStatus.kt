package com.example.data.sync

/**
 * O desfecho de um ciclo de sincronização (T16.6).
 *
 * ## Por que não um `isLoading: Boolean`
 *
 * "Sem internet", "esta conta não é a dona destes dados" e "1 item precisa de atenção" são
 * conselhos diferentes para o usuário. Um booleano colapsaria todos eles em "não deu", e a tela
 * teria que adivinhar qual — que é exatamente o erro que o backup e o restore já evitam com
 * estados próprios.
 */
sealed interface SyncOutcome {

    /**
     * O ciclo rodou. Os números descrevem o que aconteceu; nenhum deles é conteúdo do usuário.
     *
     * [pausedAt] existe quando o pull parou antes do fim do change log — porque este app não sabe
     * ler alguma mudança, ou porque uma referência dela não resolve aqui. O cursor não passou
     * dela, e nada foi perdido.
     */
    data class Success(
        val pushed: Int = 0,
        val applied: Int = 0,
        val converged: Int = 0,
        /**
         * Agregados em conflito ao fim do ciclo — o "N itens precisam de atenção".
         *
         * Contagem de **itens**, não de eventos: o mesmo treino detectado no push (stale) e no
         * pull (remoto adiante) continua sendo um item só.
         */
        val conflicts: Int = 0,
        /** Exclusões locais que continuam pendentes: a T16.7 é quem as propaga. */
        val deferredDeletes: Int = 0,
        val pausedAt: SyncApplyStop? = null
    ) : SyncOutcome

    /** Não há endereço de Spark Backend neste build. Nenhuma requisição foi feita. */
    data object NotConfigured : SyncOutcome

    /**
     * O conjunto de dados deste aparelho não pertence a nenhuma Conta Spark.
     *
     * Estar logado **não** liga o sync: só um dataset adotado explicitamente (T16.4) ou
     * restaurado (T16.5) participa. É o `SYNC_NOT_ENABLED` do contrato.
     */
    data object NotEnabled : SyncOutcome

    /** O dataset tem dono e não há sessão para falar com o servidor. */
    data object AuthRequired : SyncOutcome

    /** O dataset pertence a [ownerUid] e a sessão atual é de outra conta. */
    data class AccountMismatch(val ownerUid: String) : SyncOutcome

    /** Sem rede. A Outbox continua intacta e o núcleo do Spark não muda. */
    data object Offline : SyncOutcome

    /** O servidor respondeu indisponível. Recuperável. */
    data object Unavailable : SyncOutcome

    /** O servidor pediu para diminuir o ritmo. Nada foi perdido. */
    data object RateLimited : SyncOutcome

    /** O servidor recusou a requisição. [code] é vocabulário do Spark, nunca detalhe interno. */
    data class Rejected(val code: String?) : SyncOutcome

    /**
     * Já existe uma operação de nuvem em andamento. **Não** é erro e não vira ciclo novo.
     *
     * Dois toques em "Sincronizar agora" produzem **um** ciclo — e a mesma trava impede um ciclo
     * de rodar durante um backup ou um restore, que mexem no mesmo banco.
     */
    data object AlreadyRunning : SyncOutcome
}

/**
 * O que a tela precisa saber sem disparar nada.
 *
 * Leitura pura do Room: nenhuma requisição sai por causa dela.
 */
data class SyncSnapshot(
    val ownerUid: String? = null,
    /** Alterações locais que ainda não subiram. */
    val pending: Int = 0,
    /** Alterações que o servidor recusou e que esperam a T16.7. */
    val blocked: Int = 0,
    /** Agregados com divergência preservada entre local e remoto. */
    val conflicts: Int = 0,
    /** Quando o último ciclo completou, segundo o relógio deste aparelho. */
    val lastSyncedAt: Long? = null,
    /** A posição atual no change log do servidor. Diagnóstico — nunca texto de UI. */
    val cursor: Long = 0
)
