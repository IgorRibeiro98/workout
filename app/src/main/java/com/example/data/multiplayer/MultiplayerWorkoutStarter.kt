package com.example.data.multiplayer

import com.example.data.local.WorkoutExecutionMode
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.SnapshotBuildResult
import com.example.data.repository.WorkoutRepository
import com.example.data.repository.WorkoutShareSnapshotBuilder
import com.example.domain.engine.WorkoutEngine
import com.example.domain.multiplayer.MultiplayerBlueprintExercise
import com.example.domain.multiplayer.MultiplayerError
import com.example.domain.multiplayer.MultiplayerGateway
import com.example.domain.multiplayer.MultiplayerMemberRole
import com.example.domain.multiplayer.MultiplayerOutcome
import com.example.domain.multiplayer.MultiplayerRoom
import com.example.domain.multiplayer.MultiplayerWorkoutBlueprint
import com.example.domain.social.WorkoutShareContent
import java.util.UUID

sealed interface MultiplayerStartResult {
    /** A sessão local existe (nova ou retomada) e está vinculada à sala. */
    data class Started(val room: MultiplayerRoom, val resumed: Boolean) : MultiplayerStartResult
    data class MissingExercises(val missingCanonicalIds: List<String>) : MultiplayerStartResult
    data class Rejected(val error: MultiplayerError) : MultiplayerStartResult
    /** Já existe outra sessão em andamento neste aparelho — o início não substitui treino. */
    data object WorkoutAlreadyInProgress : MultiplayerStartResult
    data class Blocked(val reasons: List<String>) : MultiplayerStartResult
}

/**
 * Os dois caminhos que ligam uma sala a uma sessão **deste** aparelho (T19.5).
 *
 * ## Host
 *
 * ```text
 * template local → snapshot portável (o mesmo do compartilhamento) → POST rooms → sessão local DUO_REMOTE
 * ```
 *
 * ## Convidado
 *
 * ```text
 * POST join → snapshot da sala → cópia local do treino (catálogo canônico) → sessão local DUO_REMOTE
 * ```
 *
 * A cópia é **do convidado**: um `WorkoutTemplate` novo no programa atual, com `syncId` próprio,
 * sem carga, nota ou máquina — exatamente como uma oferta de treino aceita (T17.7). Nunca há
 * vínculo vivo com o treino do host; a sala carrega o snapshot, e o snapshot é imutável.
 *
 * ## Idempotência
 *
 * Um vínculo `(sala, conta)` existente com sessão `IN_PROGRESS` é **retomado**, não recriado —
 * toque duplo, reabrir o convite, ou o app reaberto depois de morrer entre o join e a tela. A
 * sessão local é uma só por sala e por conta, e o `join` no servidor é idempotente por construção.
 */
class MultiplayerWorkoutStarter(
    private val gateway: MultiplayerGateway,
    private val engine: WorkoutEngine,
    private val repository: WorkoutRepository,
    private val snapshotBuilder: WorkoutShareSnapshotBuilder = WorkoutShareSnapshotBuilder(),
    private val clientRequestIds: () -> String = { UUID.randomUUID().toString() }
) {

    /** O treino portável de um template deste aparelho — ou o motivo de ele não poder ir para a sala. */
    suspend fun blueprintFor(templateId: Long): Result<MultiplayerWorkoutBlueprint> {
        val template = repository.dao.getTemplateById(templateId)
            ?: return Result.failure(IllegalArgumentException("Treino não encontrado."))
        val exercises = repository.dao.getTemplateExercisesWithDetails(templateId)
        return when (val built = snapshotBuilder.buildSnapshot(template, exercises)) {
            is SnapshotBuildResult.Blocked -> Result.failure(IllegalStateException(built.reasons.joinToString(" ")))
            is SnapshotBuildResult.Success -> {
                val content = built.content as? WorkoutShareContent.Workout
                    ?: return Result.failure(IllegalStateException("Conteúdo inesperado."))
                Result.success(
                    MultiplayerWorkoutBlueprint(
                        name = content.snapshot.name,
                        shortIdentifier = content.snapshot.shortIdentifier,
                        exercises = content.snapshot.exercises.map {
                            MultiplayerBlueprintExercise(
                                canonicalExerciseId = it.canonicalExerciseId,
                                sortOrder = it.sortOrder,
                                targetSets = it.targetSets,
                                minReps = it.minReps,
                                maxReps = it.maxReps,
                                restDurationSeconds = it.restDurationSeconds
                            )
                        }
                    )
                )
            }
        }
    }

    /** Host: cria a sala com o treino de [templateId] para [inviteeSocialId]. Não inicia a sessão. */
    suspend fun createRoom(templateId: Long, inviteeSocialId: String): MultiplayerStartResult {
        val blueprint = blueprintFor(templateId).getOrElse { failure ->
            return MultiplayerStartResult.Blocked(listOf(failure.message ?: "Este treino não pode ser usado em dupla."))
        }
        return when (val outcome = gateway.createRoom(clientRequestIds(), inviteeSocialId, blueprint)) {
            is MultiplayerOutcome.Success -> MultiplayerStartResult.Started(outcome.data, resumed = false)
            is MultiplayerOutcome.Failure -> MultiplayerStartResult.Rejected(outcome.error)
        }
    }

    /**
     * Host: inicia a sessão local do template com que a sala foi criada, vinculada à sala. O
     * host pode começar antes de o convidado entrar — a sala continua `WAITING` e a tela mostra.
     */
    suspend fun startAsHost(room: MultiplayerRoom, templateId: Long, accountUid: String): MultiplayerStartResult {
        repository.dao.getMultiplayerLinkForRoom(room.roomId, accountUid)?.let { existing ->
            val session = repository.dao.getSessionById(existing.sessionId)
            if (session?.status == com.example.data.local.SessionStatus.IN_PROGRESS.name) {
                return MultiplayerStartResult.Started(room, resumed = true)
            }
        }
        if (repository.dao.getActiveSession() != null) return MultiplayerStartResult.WorkoutAlreadyInProgress
        engine.startSession(
            templateId = templateId,
            mode = WorkoutExecutionMode.DUO_REMOTE,
            multiplayer = WorkoutEngine.MultiplayerSessionLink(
                roomId = room.roomId,
                accountUid = accountUid,
                role = MultiplayerMemberRole.HOST.name,
                peerDisplayName = room.peer?.displayName
            )
        )
        return MultiplayerStartResult.Started(room, resumed = false)
    }

    /**
     * Convidado: entra na sala (idempotente), cria a cópia local do treino e inicia a sessão.
     *
     * A ordem é servidor primeiro: é ele quem sabe se a sala ainda existe para esta conta —
     * bloqueio, encerramento e expiração são revalidados no `join`, e uma recusa lá não deixa
     * nada gravado aqui.
     */
    suspend fun joinAndStart(roomId: String, accountUid: String): MultiplayerStartResult {
        repository.dao.getMultiplayerLinkForRoom(roomId, accountUid)?.let { existing ->
            val session = repository.dao.getSessionById(existing.sessionId)
            if (session?.status == com.example.data.local.SessionStatus.IN_PROGRESS.name) {
                return when (val outcome = gateway.join(roomId)) {
                    is MultiplayerOutcome.Success -> MultiplayerStartResult.Started(outcome.data, resumed = true)
                    is MultiplayerOutcome.Failure -> MultiplayerStartResult.Rejected(outcome.error)
                }
            }
        }
        if (repository.dao.getActiveSession() != null) return MultiplayerStartResult.WorkoutAlreadyInProgress

        val room = when (val outcome = gateway.join(roomId)) {
            is MultiplayerOutcome.Success -> outcome.data
            is MultiplayerOutcome.Failure -> return MultiplayerStartResult.Rejected(outcome.error)
        }

        val resolved = resolveExercises(room.workout.exercises)
            ?: return MultiplayerStartResult.MissingExercises(missingCanonicalIds(room.workout.exercises))
        val program = repository.getProgramForNewTemplate()
            ?: return MultiplayerStartResult.Blocked(listOf("Nenhum programa de treino encontrado."))
        val nextOrder = (repository.dao.getTemplatesForProgramSync(program.id).maxOfOrNull { it.orderInProgram } ?: -1) + 1

        val templateId = repository.addTemplateWithExercises(
            template = WorkoutTemplateEntity(
                programId = program.id,
                name = room.workout.name,
                shortIdentifier = room.workout.shortIdentifier ?: "T",
                orderInProgram = nextOrder
            ),
            exercises = resolved
        )

        engine.startSession(
            templateId = templateId,
            mode = WorkoutExecutionMode.DUO_REMOTE,
            multiplayer = WorkoutEngine.MultiplayerSessionLink(
                roomId = room.roomId,
                accountUid = accountUid,
                role = MultiplayerMemberRole.GUEST.name,
                peerDisplayName = room.peer?.displayName
            )
        )
        return MultiplayerStartResult.Started(room, resumed = false)
    }

    private suspend fun resolveExercises(exercises: List<MultiplayerBlueprintExercise>): List<WorkoutTemplateExerciseEntity>? {
        val resolved = mutableListOf<WorkoutTemplateExerciseEntity>()
        for (ex in exercises.sortedBy { it.sortOrder }) {
            val entity = repository.getExerciseByCanonicalId(ex.canonicalExerciseId) ?: return null
            resolved.add(
                WorkoutTemplateExerciseEntity(
                    templateId = 0,
                    exerciseId = entity.id,
                    sortOrder = ex.sortOrder,
                    targetSets = ex.targetSets,
                    minReps = ex.minReps,
                    maxReps = ex.maxReps,
                    restDurationSeconds = ex.restDurationSeconds,
                    plannedWeight = null,
                    machineLabel = null,
                    notes = null
                )
            )
        }
        return resolved
    }

    private suspend fun missingCanonicalIds(exercises: List<MultiplayerBlueprintExercise>): List<String> =
        exercises.map { it.canonicalExerciseId }.distinct().filter { repository.getExerciseByCanonicalId(it) == null }
}
