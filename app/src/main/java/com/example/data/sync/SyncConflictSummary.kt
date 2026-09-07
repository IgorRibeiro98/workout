package com.example.data.sync

import com.example.data.backup.BackupCanonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Um conflito como a tela precisa vê-lo (T16.7).
 *
 * ```text
 * SyncConflictEntity          →  SyncConflictSummary
 * kind, revision, hash, uid      título, duas versões, e o que dá para escolher
 * ```
 *
 * ## Por que existe uma tradução
 *
 * `baseRevision=4`, `remoteRevision=5` e `payloadHash` não ajudam ninguém a decidir qual treino
 * manter. O que ajuda é o nome dos dois lados e quantos exercícios cada um tem. Esta camada existe
 * para que a tela mostre isso **sem** conhecer `SyncEntityType`, DAO, Outbox ou revision — há teste
 * estrutural que falha se ela conhecer.
 *
 * ## Diferenças, e não um diff genérico
 *
 * A comparação é de **poucos campos escolhidos por tipo** — nome, quantidade de exercícios, peso.
 * Um diff genérico de JSON mostraria `orderInProgram` e `restDurationSeconds` lado a lado, o que é
 * ruído para quem só quer saber qual das duas versões é a que ele reconhece. E nada disso é merge:
 * as diferenças são **mostradas**, nunca combinadas. Escolher um campo de cada lado está fora do
 * escopo, de propósito.
 */
data class SyncConflictSummary(

    /** A identidade opaca que a tela devolve ao resolver. */
    val id: SyncConflictId,

    /** O nome do item, como o usuário o conhece. */
    val title: String,

    val category: SyncConflictCategory,

    /** Os poucos campos que diferem entre as duas versões, quando dá para compará-los. */
    val differences: List<SyncConflictDifference>,

    /** O que o usuário pode escolher aqui. Vazio significa "informativo, sem escolha segura". */
    val choices: List<SyncConflictChoice>,

    /**
     * O usuário já escolheu e a decisão está na fila de envio.
     *
     * A tela mostra isso em vez dos botões: tocar de novo não faria nada (a resolução é
     * idempotente), e oferecer o botão sugeriria que a primeira escolha se perdeu.
     */
    val awaitingPush: Boolean
)

/** O que aconteceu, em vocabulário de produto. */
enum class SyncConflictCategory {

    /** Alterado aqui e em outro aparelho. As duas versões existem. */
    CHANGED_ON_BOTH,

    /** Excluído em outro aparelho, alterado neste. */
    DELETED_ELSEWHERE,

    /** Excluído neste aparelho, alterado em outro. */
    DELETED_HERE,

    /**
     * Um treino concluído com o mesmo registro e conteúdos diferentes.
     *
     * Não é edição concorrente: é inconsistência de histórico. Nenhuma versão é sobrescrita e
     * nenhuma escolha é oferecida — as duas afirmam ter registrado o mesmo treino, e escolher uma
     * apagaria um fato.
     */
    HISTORY_MISMATCH,

    /** O servidor recusou a alteração. É defeito, não divergência entre pessoas. */
    REJECTED
}

/** Um campo que difere entre as duas versões. Texto pronto — a tela não formata nada. */
data class SyncConflictDifference(
    val label: String,
    val local: String?,
    val remote: String?
)

/**
 * Como um conflito guardado vira algo apresentável — e quais escolhas ele admite.
 *
 * As duas coisas moram juntas porque precisam concordar: oferecer "usar a versão da nuvem" quando
 * não há cópia da nuvem guardada seria um botão que sempre falha, e é a mesma leitura que decide
 * as duas.
 */
object SyncConflictPreview {

    fun summarize(
        conflict: SyncConflictEntity,
        type: SyncEntityType,
        localPayload: JsonElement?,
        remotePayload: JsonElement?
    ): SyncConflictSummary {
        val local = localPayload as? JsonObject
        val remote = remotePayload as? JsonObject
        return SyncConflictSummary(
            id = SyncConflictId(conflict.entityType, conflict.entitySyncId),
            title = titleOf(type, local, remote),
            category = categoryOf(conflict),
            differences = differencesOf(type, local, remote),
            choices = choicesFor(conflict, type).toList(),
            awaitingPush = conflict.status == SyncConflictStatus.AWAITING_PUSH.name
        )
    }

    /**
     * As escolhas que fazem sentido para este conflito.
     *
     * A política do agregado entra aqui: histórico concluído divergente não recebe escolha nenhuma
     * ([SyncConflictStrategy.IMMUTABLE_CONFLICT]), porque "manter local" e "usar remoto"
     * sobrescreveriam um registro do que aconteceu.
     */
    fun choicesFor(conflict: SyncConflictEntity, type: SyncEntityType): Set<SyncConflictChoice> {
        if (conflict.status == SyncConflictStatus.AWAITING_PUSH.name) return emptySet()

        val kind = SyncConflictKind.entries.firstOrNull { it.name == conflict.kind } ?: return emptySet()
        val policy = SyncEntityPolicies.of(type)
        if (policy.conflictStrategy != SyncConflictStrategy.USER_CHOICE) return emptySet()

        return when (kind) {
            SyncConflictKind.STALE_LOCAL_CHANGE, SyncConflictKind.REMOTE_AHEAD_LOCAL_DIRTY ->
                buildSet {
                    add(SyncConflictChoice.KEEP_LOCAL)
                    // Sem cópia remota guardada não há o que aplicar. Ela chega no pull seguinte,
                    // e até lá a tela oferece só a opção que funciona.
                    if (hasRemoteCopy(conflict)) add(SyncConflictChoice.USE_REMOTE)
                }

            SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED -> buildSet {
                add(SyncConflictChoice.CONFIRM_REMOTE_DELETE)
                // Recriar exige uma identidade nova, e ela só é segura para agregados que nada
                // mais referencia por `localId`. Um programa apagado leva os treinos junto; um
                // exercício pessoal é apontado por treinos (`ON DELETE RESTRICT`). Recriar
                // qualquer um dos dois exigiria reescrever os agregados que dependem dele — outra
                // decisão, para outra tarefa.
                if (supportsRecreate(type)) add(SyncConflictChoice.KEEP_LOCAL_AS_NEW)
            }

            SyncConflictKind.LOCAL_DELETED_REMOTE_MODIFIED -> buildSet {
                add(SyncConflictChoice.CONFIRM_LOCAL_DELETE)
                if (hasRemoteCopy(conflict)) add(SyncConflictChoice.USE_REMOTE)
            }

            // Divergência de histórico e recusa do servidor são informativas: nenhuma escolha
            // entre as duas versões seria segura, e inventar uma seria pior do que dizer a verdade.
            SyncConflictKind.IMMUTABLE_HISTORY,
            SyncConflictKind.REJECTED_BY_SERVER,
            SyncConflictKind.IDEMPOTENCY -> emptySet()
        }
    }

    /**
     * A cópia remota guardada ainda corresponde ao hash que o servidor calculou.
     *
     * Reconferir antes de escrever custa um SHA-256 e evita a única forma de corrupção silenciosa
     * possível aqui: aplicar como "versão da nuvem" um texto que deixou de ser o que o servidor
     * disse que era. HTTPS protegeu o transporte; isto protege o que ficou guardado.
     */
    fun remoteCopyIsIntact(conflict: SyncConflictEntity): Boolean {
        val payload = conflict.remotePayload ?: return false
        val expected = conflict.remotePayloadHash ?: return false
        return try {
            BackupCanonicalJson.canonicalHash(Json.parseToJsonElement(payload)).hash == expected
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun hasRemoteCopy(conflict: SyncConflictEntity): Boolean =
        conflict.remotePayload != null && conflict.remotePayloadHash != null

    /**
     * Recriar com identidade nova só é oferecido para agregados que nada mais referencia
     * localmente.
     *
     * `WORKOUT_PROGRAM` e `CUSTOM_EXERCISE` ficam de fora: apagar o programa levaria os treinos
     * dele, e o exercício é apontado por linhas de treino com `ON DELETE RESTRICT`. Recriar
     * qualquer um exigiria reescrever os agregados dependentes junto — e um "manter" que reescreve
     * outras coisas não é o que a pessoa pediu.
     */
    private fun supportsRecreate(type: SyncEntityType): Boolean = when (type) {
        SyncEntityType.WORKOUT_PROGRAM, SyncEntityType.CUSTOM_EXERCISE -> false
        else -> true
    }

    private fun categoryOf(conflict: SyncConflictEntity): SyncConflictCategory =
        when (SyncConflictKind.entries.firstOrNull { it.name == conflict.kind }) {
            SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED -> SyncConflictCategory.DELETED_ELSEWHERE
            SyncConflictKind.LOCAL_DELETED_REMOTE_MODIFIED -> SyncConflictCategory.DELETED_HERE
            SyncConflictKind.IMMUTABLE_HISTORY -> SyncConflictCategory.HISTORY_MISMATCH
            SyncConflictKind.REJECTED_BY_SERVER, SyncConflictKind.IDEMPOTENCY ->
                SyncConflictCategory.REJECTED
            else -> SyncConflictCategory.CHANGED_ON_BOTH
        }

    private fun titleOf(type: SyncEntityType, local: JsonObject?, remote: JsonObject?): String {
        val field = when (type) {
            SyncEntityType.WORKOUT_SESSION -> "templateNameSnapshot"
            SyncEntityType.CHECK_IN -> "gymName"
            SyncEntityType.BODY_MEASUREMENT -> null
            else -> "name"
        }
        val name = field?.let { text(local, it) ?: text(remote, it) }
        return name?.takeIf { it.isNotBlank() } ?: fallbackTitle(type)
    }

    private fun fallbackTitle(type: SyncEntityType): String = when (type) {
        SyncEntityType.WORKOUT_PROGRAM -> "Programa de treino"
        SyncEntityType.WORKOUT_TEMPLATE -> "Treino"
        SyncEntityType.WORKOUT_SESSION -> "Treino concluído"
        SyncEntityType.CUSTOM_EXERCISE -> "Exercício personalizado"
        SyncEntityType.BODY_MEASUREMENT -> "Medida corporal"
        SyncEntityType.CHECK_IN -> "Check-in"
    }

    private fun differencesOf(
        type: SyncEntityType,
        local: JsonObject?,
        remote: JsonObject?
    ): List<SyncConflictDifference> {
        if (local == null && remote == null) return emptyList()
        val fields = when (type) {
            SyncEntityType.WORKOUT_TEMPLATE -> listOf(
                Field("Nome") { text(it, "name") },
                Field("Exercícios") { count(it, "exercises") }
            )
            SyncEntityType.WORKOUT_PROGRAM -> listOf(Field("Nome") { text(it, "name") })
            SyncEntityType.CUSTOM_EXERCISE -> listOf(
                Field("Nome") { text(it, "name") },
                Field("Músculo") { text(it, "primaryMuscle") }
            )
            SyncEntityType.BODY_MEASUREMENT -> listOf(Field("Peso") { text(it, "weightKg") })
            SyncEntityType.CHECK_IN -> listOf(Field("Academia") { text(it, "gymName") })
            SyncEntityType.WORKOUT_SESSION -> listOf(
                Field("Treino") { text(it, "templateNameSnapshot") },
                Field("Exercícios") { count(it, "exercises") }
            )
        }
        return fields
            .map { SyncConflictDifference(it.label, it.read(local), it.read(remote)) }
            // Um campo igual dos dois lados não é diferença, e mostrá-lo esconderia a que importa.
            .filter { it.local != it.remote }
    }

    private fun text(payload: JsonObject?, field: String): String? =
        (payload?.get(field) as? JsonPrimitive)?.takeIf { !it.isString || it.content.isNotEmpty() }
            ?.content
            ?.takeIf { it != "null" }

    private fun count(payload: JsonObject?, field: String): String? =
        (payload?.get(field) as? JsonArray)?.size?.toString()

    private class Field(val label: String, val read: (JsonObject?) -> String?)
}
