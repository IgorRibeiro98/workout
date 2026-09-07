package com.example.data.sync

/**
 * A pergunta que a T16.7 não fazia: *a cópia da nuvem que o usuário está vendo ainda é a atual?*
 * (T16.7.1)
 *
 * ```text
 * conflito guarda remoteRevision = 5
 *        ↓
 * GET /v1/sync/entities/...      ← estado de AGORA, por identidade
 *        ↓
 * servidor diz 5  →  a decisão vale, e a cópia guardada pode ser aplicada
 * servidor diz 6  →  a decisão NÃO vale: o conflito é atualizado e o usuário escolhe de novo
 * ```
 *
 * ## Por que existe uma função pura para isto
 *
 * Comparar revision, hash e tombstone é a regra que decide se um dado local vai ser sobrescrito.
 * Ela precisa ser exercitável sem banco, sem rede e sem transação — inclusive nos casos que o
 * protocolo diz serem impossíveis, que são exatamente os que ninguém consegue montar num teste de
 * ponta a ponta.
 *
 * ## O que ela nunca faz
 *
 * Não escreve, não escolhe e não "aproxima". Um estado remoto que não corresponde ao pedido, ou
 * que contradiz o tombstone que o conflito registrou, não é reconciliado por heurística: ele é
 * recusado, e nada local é tocado.
 */
object SyncRemotePreflight {

    /**
     * Compara o lado remoto guardado no conflito com o estado que o servidor acabou de reportar.
     *
     * [state] é a resposta de `GET /v1/sync/entities/...`. A conta já foi revalidada por quem
     * chama — aqui só se decide se **o conteúdo** ainda é o mesmo.
     */
    fun evaluate(
        conflict: SyncConflictEntity,
        type: SyncEntityType,
        state: SyncEntityStateDto
    ): SyncRemotePreflightVerdict {
        // A resposta precisa ser sobre o que foi perguntado. Um servidor que devolvesse outra
        // identidade não é um caso a tratar: é um caso a recusar.
        if (state.entityType != type.name || state.entitySyncId != conflict.entitySyncId) {
            return SyncRemotePreflightVerdict.Mismatched
        }

        val remoteWasDeleted = conflict.kind == SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED.name

        // Tombstone que voltou a ser entidade viva. O protocolo diz que isso não acontece — a
        // garantia é do banco do servidor (`AND sync_entities.deleted = 0`), não da disciplina do
        // serviço. Se acontecer mesmo assim, é violação de integridade, e aceitar em silêncio
        // seria participar de uma ressurreição.
        if (remoteWasDeleted && !state.deleted) return SyncRemotePreflightVerdict.Resurrected

        // A revision é o árbitro — nunca relógio, nunca `updatedAt`. Uma revision desconhecida
        // (`null`) também é "mudou": não há como afirmar que a cópia guardada é a atual.
        if (conflict.remoteRevision == null) return SyncRemotePreflightVerdict.Changed
        if (state.serverRevision != conflict.remoteRevision) return SyncRemotePreflightVerdict.Changed

        // A entidade virou tombstone. Aplicar o conteúdo guardado aqui seria escrever localmente
        // algo que a nuvem já não tem.
        if (state.deleted != remoteWasDeleted) return SyncRemotePreflightVerdict.Changed

        // Mesma revision com conteúdo outro é impossível pelo protocolo — e, sendo impossível, não
        // é algo sobre o que valha a pena adivinhar. O conflito é atualizado e o usuário revê.
        if (!state.deleted && state.payloadHash != conflict.remotePayloadHash) {
            return SyncRemotePreflightVerdict.Changed
        }

        return SyncRemotePreflightVerdict.Current
    }
}

/** O que a comparação concluiu. */
sealed interface SyncRemotePreflightVerdict {

    /** O servidor confirma o que o conflito guardou. A decisão do usuário pode ser aplicada. */
    data object Current : SyncRemotePreflightVerdict

    /**
     * O servidor avançou desde que aquela cópia foi obtida.
     *
     * O lado remoto do conflito é substituído pelo estado de agora, o status volta para
     * `PENDING`, e **nada** local é escrito: a escolha anterior descrevia um estado que já não
     * existe, e reaproveitá-la ("ele já escolheu remoto, então usa a revision nova") aplicaria um
     * conteúdo que o usuário nunca viu.
     */
    data object Changed : SyncRemotePreflightVerdict

    /** A resposta não é sobre a identidade que foi perguntada. Nada é aplicado. */
    data object Mismatched : SyncRemotePreflightVerdict

    /** O que o conflito registrou como tombstone voltou vivo. Violação de integridade. */
    data object Resurrected : SyncRemotePreflightVerdict
}
