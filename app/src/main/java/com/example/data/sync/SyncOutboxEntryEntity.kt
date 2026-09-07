package com.example.data.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Uma alteração local que futuramente poderá precisar ser entregue ao Spark Backend (T16.3).
 *
 * É *Transactional Outbox*: a entrada nasce na **mesma transação Room** que a alteração de
 * domínio que ela descreve (ver [SyncMutationCoordinator]). Não é fila em memória, não é arquivo
 * separado, não é `StateFlow` — se o processo morrer entre a escrita e o registro, nenhum dos dois
 * existe.
 *
 * ## O que esta linha guarda — e o que não guarda
 *
 * Ela guarda **referência**, não snapshot: tipo, identidade global e operação. O conteúdo a enviar
 * é montado a partir do Room no momento do push (T16.6). Ver a justificativa em
 * `docs/architecture/sync-protocol.md`.
 *
 * ## Na T16.3, nada aqui é enviado
 *
 * Não existe worker, HTTP, retry ou acknowledgment. E, com sync desabilitado — o padrão —, nem
 * entradas são produzidas: mudanças anteriores à ativação da nuvem entram no snapshot inicial da
 * T16.4, não em uma fila histórica reconstruída desde a instalação.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        // Idempotência: o servidor reconhece um reenvio por `clientMutationId` (T16.6). Duas
        // linhas com o mesmo id seriam duas mutações se passando por uma.
        Index(value = ["clientMutationId"], unique = true),
        // O push lê "as pendentes desta conta, em ordem" — sem varrer a Outbox inteira.
        Index(value = ["ownerUid", "status", "id"]),
        // A coalescência procura a última entrada de um agregado antes de registrar outra.
        Index(value = ["entityType", "entitySyncId"])
    ]
)
data class SyncOutboxEntryEntity(

    /**
     * Ordem de intenção. É `localId`: só ordena a fila deste aparelho e nunca sai daqui.
     *
     * O push processa por `id` crescente, para que `UPSERT` seguido de `DELETE` do mesmo agregado
     * chegue nessa ordem.
     */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Identidade da **alteração** — UUID, único globalmente.
     *
     * Não confundir com [entitySyncId]: `entitySyncId` é *qual treino*, `clientMutationId` é
     * *qual edição daquele treino*. É o que permite ao servidor distinguir "mandaram de novo
     * porque a resposta se perdeu" de "mudaram de novo".
     */
    val clientMutationId: String,

    /**
     * A conta que poderá enviar esta mutação — o `uid` do Firebase Auth.
     *
     * Nunca ambíguo: uma entrada só é produzida quando a nuvem já está associada a uma conta
     * explicitamente (T16.4). Sem isso, uma fila criada antes do login não teria dono, e adotá-la
     * na primeira conta que entrasse seria dar a ela dados de outra pessoa.
     */
    val ownerUid: String,

    /** Nome de [SyncEntityType]. */
    val entityType: String,

    /** Identidade global do agregado (`syncId`). */
    val entitySyncId: String,

    /** Nome de [SyncOperation]. */
    val operation: String,

    /** Nome de [SyncOutboxStatus]. */
    val status: String = SyncOutboxStatus.PENDING.name,

    /**
     * Quando a intenção foi registrada, em epoch millis (UTC).
     *
     * Metadado de auditoria e ordenação para humanos. **Não** é árbitro de conflito: relógio de
     * aparelho diverge, e quem decide versão é o servidor (`revision`/`changeSeq`, T16.6).
     */
    val createdAt: Long,

    /** Quantas vezes o envio já foi tentado. Zero enquanto não existir transporte. */
    val attemptCount: Int = 0,

    /** Quando foi a última tentativa, em epoch millis (UTC). Nulo enquanto nunca tentado. */
    val lastAttemptAt: Long? = null,

    /**
     * Por que a entrada saiu da fila de envio (T16.6). Nulo enquanto ela está [SyncOutboxStatus.PENDING].
     *
     * Vocabulário técnico curto — `STALE`, `IMMUTABLE_HISTORY_CONFLICT`, `INVALID`, `UNSUPPORTED`,
     * `IDEMPOTENCY_CONFLICT` — e nunca conteúdo do usuário. É o que permite a uma tela dizer "1
     * item precisa de atenção" sem inventar um diagnóstico.
     */
    val blockedReason: String? = null
)
