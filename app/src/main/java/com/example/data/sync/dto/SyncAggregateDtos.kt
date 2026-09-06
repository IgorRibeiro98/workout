package com.example.data.sync.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Contratos de serialização dos agregados sincronizáveis (T16.3).
 *
 * ## Por que não mandar a entidade do Room
 *
 * `WorkoutTemplateEntity` é modelo de **persistência**: ela existe para desenhar tela rápido e
 * muda quando a tela muda. Se ela fosse o contrato remoto, renomear uma coluna viraria quebra de
 * compatibilidade entre aparelhos, e `templateId = 7` — que não significa nada no outro celular —
 * atravessaria a rede como se significasse.
 *
 * Os DTOs aqui são o terceiro modelo, ao lado de persistência e domínio:
 *
 * ```text
 * Room Entity   →  como o dado é guardado neste aparelho
 * Domain model  →  como a regra de negócio o enxerga
 * Sync DTO      →  como ele é descrito para outro aparelho          ← este arquivo
 * ```
 *
 * Regras que valem para todos:
 *
 * - **nenhum `localId` aparece.** Referência entre agregados é sempre `syncId` ou `canonicalId`;
 * - **ordem é explícita.** `position`, `plannedOrder` e `executionOrder` são dados de domínio, não
 *   consequência de `id` autoincrement nem de `createdAt`;
 * - **tempo é epoch millis UTC.** Sem fuso do aparelho no contrato.
 */

/**
 * O envelope de um agregado serializado.
 *
 * [schemaVersion] é a versão **deste payload**, por agregado, e não se confunde com a versão da
 * API HTTP, com a versão do banco Room nem com a versão do formato de backup. É o que permite ao
 * formato de um treino evoluir sem depender do número da tabela local — um aparelho antigo pode
 * mandar `WORKOUT_TEMPLATE` v1 enquanto outro já manda v2, e o servidor sabe qual leitor usar.
 */
@Serializable
data class SyncAggregateEnvelope(
    val entityType: String,
    val entitySyncId: String,
    val schemaVersion: Int,
    val payload: JsonElement
)

/**
 * Como uma entidade pessoal referencia um exercício.
 *
 * Nunca por `localId`, e nunca por nome. Duas identidades legítimas, e a distinção entre catálogo
 * e criação do usuário é preservada:
 *
 * - `CANONICAL` → [id] é o `canonicalId` do manifesto versionado;
 * - `CUSTOM` → [id] é o `syncId` do exercício criado pelo usuário.
 */
@Serializable
data class ExerciseRefDto(
    val kind: String,
    val id: String
) {
    companion object {
        const val CANONICAL = "CANONICAL"
        const val CUSTOM = "CUSTOM"
    }
}

@Serializable
data class WorkoutProgramSyncDto(
    val syncId: String,
    val name: String,
    val description: String? = null,
    val isCurrent: Boolean = false,
    /** Identidade de conteúdo do programa importado de manifesto. Não é `syncId`. */
    val externalId: String? = null,
    val contentVersion: Int = 0
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

/** Uma linha do treino: exercício, ordem e configuração de séries. Não tem identidade própria. */
@Serializable
data class WorkoutTemplateExerciseSyncDto(
    val position: Int,
    val exercise: ExerciseRefDto,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restDurationSeconds: Int,
    val plannedWeight: Float? = null,
    val machineLabel: String? = null,
    val notes: String? = null
)

/**
 * O agregado `WORKOUT_TEMPLATE` inteiro: raiz + exercícios + ordem + configuração.
 *
 * Um snapshot, e não quatro mutações independentes. Renomear o treino, mover um exercício e mudar
 * a carga de uma série produzem todos o mesmo push: "este treino agora é assim".
 */
@Serializable
data class WorkoutTemplateSyncDto(
    val syncId: String,
    val programSyncId: String? = null,
    val name: String,
    val shortIdentifier: String? = null,
    val orderInProgram: Int = 0,
    val dayOfWeek: String? = null,
    val exercises: List<WorkoutTemplateExerciseSyncDto> = emptyList()
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

@Serializable
data class SetLogSyncDto(
    val setNumber: Int,
    val type: String,
    val weight: Float,
    val repetitions: Int,
    val completed: Boolean,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val rpe: Float? = null,
    val rir: Int? = null,
    val durationSeconds: Int? = null
)

@Serializable
data class ExerciseSessionSyncDto(
    val plannedOrder: Int,
    val executionOrder: Int,
    /**
     * O nome como estava na hora do treino.
     *
     * Continua no snapshot de propósito: reconstruir histórico a partir do nome **atual** de um
     * exercício mutável reescreveria o passado quando o usuário renomeasse algo.
     */
    val exerciseNameSnapshot: String,
    val plannedExercise: ExerciseRefDto? = null,
    val actualExercise: ExerciseRefDto? = null,
    val machineLabelSnapshot: String? = null,
    val primaryMuscleSnapshot: String? = null,
    val restDurationSecondsSnapshot: Int? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val notes: String? = null,
    val replacementReason: String? = null,
    val sets: List<SetLogSyncDto> = emptyList()
)

/**
 * O agregado `WORKOUT_SESSION`: a sessão, seus exercícios e as séries executadas.
 *
 * Para uma sessão `COMPLETED` isto é **histórico imutável**. Ter `syncId` permite ao servidor
 * reconhecer a mesma sessão; não autoriza editá-la, reescrever séries antigas nem recalcular o
 * que aconteceu.
 */
@Serializable
data class WorkoutSessionSyncDto(
    val syncId: String,
    val templateSyncId: String? = null,
    val templateNameSnapshot: String? = null,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val notes: String? = null,
    val exercises: List<ExerciseSessionSyncDto> = emptyList()
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

@Serializable
data class CustomExerciseSyncDto(
    val syncId: String,
    val name: String,
    val primaryMuscle: String? = null,
    val equipment: String? = null,
    val description: String? = null,
    val isBodyweight: Boolean = false,
    val rirEnabled: Boolean = false,
    val active: Boolean = true
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

@Serializable
data class BodyMeasurementSyncDto(
    val syncId: String,
    val date: Long,
    val createdAt: Long,
    val weightKg: Float? = null,
    val heightCm: Float? = null,
    val bodyFatPercentage: Float? = null,
    val waistCm: Float? = null,
    val abdomenCm: Float? = null,
    val chestCm: Float? = null,
    val leftArmCm: Float? = null,
    val rightArmCm: Float? = null,
    val leftThighCm: Float? = null,
    val rightThighCm: Float? = null,
    val calfCm: Float? = null,
    val hipCm: Float? = null
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

@Serializable
data class CheckInSyncDto(
    val syncId: String,
    val checkInTime: Long,
    val checkOutTime: Long? = null,
    val gymName: String? = null,
    /** A sessão de treino ligada a este check-in, por identidade global. Nunca por `localId`. */
    val sessionSyncId: String? = null
) {
    companion object { const val SCHEMA_VERSION = 1 }
}
