package com.example.data.sync

import com.example.data.local.BodyMeasurementDao
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.sync.dto.BodyMeasurementSyncDto
import com.example.data.sync.dto.CheckInSyncDto
import com.example.data.sync.dto.CustomExerciseSyncDto
import com.example.data.sync.dto.ExerciseRefDto
import com.example.data.sync.dto.ExerciseSessionSyncDto
import com.example.data.sync.dto.SetLogSyncDto
import com.example.data.sync.dto.SyncAggregateEnvelope
import com.example.data.sync.dto.WorkoutProgramSyncDto
import com.example.data.sync.dto.WorkoutSessionSyncDto
import com.example.data.sync.dto.WorkoutTemplateExerciseSyncDto
import com.example.data.sync.dto.WorkoutTemplateSyncDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Monta o conteúdo de um agregado a partir do Room, pela identidade global (T16.3).
 *
 * ## Por que a Outbox guarda referência e não snapshot
 *
 * Havia duas estratégias possíveis. A escolhida é **referência**: a entrada da Outbox registra
 * tipo, `syncId` e operação, e o conteúdo é montado aqui na hora do envio.
 *
 * A alternativa — congelar o payload dentro da entrada — foi recusada por quatro motivos concretos
 * neste app:
 *
 * 1. **Segunda autoridade.** Um snapshot guardado é uma cópia que começa a divergir do Room no
 *    instante seguinte. O Spark tem uma autoridade local, e ela é o Room.
 * 2. **Estado errado no ar.** Com snapshot, três edições seguidas do mesmo treino enviariam
 *    versões intermediárias que o usuário já abandonou. Com referência, o que sobe é o que existe.
 * 3. **Idempotência simples.** Reenviar é reler o estado atual: a mesma mutação reenviada produz
 *    o mesmo resultado, sem precisar reconciliar payloads antigos.
 * 4. **Volume.** Uma sessão concluída com todas as séries é grande. Duplicá-la na fila a cada
 *    alteração multiplicaria o banco local sem necessidade.
 *
 * O preço, assumido: um `DELETE` não pode montar payload — e não precisa, porque uma exclusão
 * carrega apenas tipo e identidade.
 *
 * ## Nada aqui envia
 *
 * Este montador não conhece HTTP, worker, retry ou backend. Ele existe para que a T16.4 consiga
 * produzir o snapshot inicial e a T16.6 o push — sem precisar de outra migração de schema.
 */
class SyncAggregateSnapshotBuilder(
    private val workoutDao: WorkoutDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val json: Json = Json { encodeDefaults = true }
) {

    /**
     * O agregado como envelope serializado, ou `null` se ele não existe mais localmente.
     *
     * `null` não é erro: entre registrar a intenção e enviá-la, o usuário pode ter apagado o item.
     * Quem chama decide o que fazer — a T16.6 tratará isso junto com o tombstone.
     */
    suspend fun snapshot(entityType: SyncEntityType, entitySyncId: String): SyncAggregateEnvelope? =
        when (entityType) {
            SyncEntityType.WORKOUT_PROGRAM -> program(entitySyncId)
            SyncEntityType.WORKOUT_TEMPLATE -> template(entitySyncId)
            SyncEntityType.WORKOUT_SESSION -> session(entitySyncId)
            SyncEntityType.CUSTOM_EXERCISE -> customExercise(entitySyncId)
            SyncEntityType.BODY_MEASUREMENT -> bodyMeasurement(entitySyncId)
            SyncEntityType.CHECK_IN -> checkIn(entitySyncId)
        }

    private suspend fun program(syncId: String): SyncAggregateEnvelope? {
        val program = workoutDao.getProgramBySyncId(syncId) ?: return null
        return envelope(
            SyncEntityType.WORKOUT_PROGRAM,
            syncId,
            WorkoutProgramSyncDto.SCHEMA_VERSION,
            WorkoutProgramSyncDto(
                syncId = program.syncId,
                name = program.name,
                description = program.description,
                isCurrent = program.isCurrent,
                externalId = program.externalId,
                contentVersion = program.contentVersion
            )
        )
    }

    private suspend fun template(syncId: String): SyncAggregateEnvelope? {
        val template = workoutDao.getTemplateBySyncId(syncId) ?: return null
        // `getTemplateExercisesWithDetails` já devolve ordenado por `sortOrder`. A posição vai
        // explícita no payload: a ordem é dado de domínio e não pode depender de como a lista
        // chegou nem do `id` das linhas.
        val exercises = workoutDao.getTemplateExercisesWithDetails(template.id)
            .mapIndexed { index, item ->
                WorkoutTemplateExerciseSyncDto(
                    position = index,
                    // Sem identidade global do exercício não há snapshot correto a montar.
                    exercise = exerciseRef(item.exercise) ?: return null,
                    targetSets = item.templateExercise.targetSets,
                    minReps = item.templateExercise.minReps,
                    maxReps = item.templateExercise.maxReps,
                    restDurationSeconds = item.templateExercise.restDurationSeconds,
                    plannedWeight = item.templateExercise.plannedWeight,
                    machineLabel = item.templateExercise.machineLabel,
                    notes = item.templateExercise.notes
                )
            }
        return envelope(
            SyncEntityType.WORKOUT_TEMPLATE,
            syncId,
            WorkoutTemplateSyncDto.SCHEMA_VERSION,
            WorkoutTemplateSyncDto(
                syncId = template.syncId,
                programSyncId = workoutDao.getProgramById(template.programId)?.syncId,
                name = template.name,
                shortIdentifier = template.shortIdentifier,
                orderInProgram = template.orderInProgram,
                dayOfWeek = template.dayOfWeek,
                exercises = exercises
            )
        )
    }

    private suspend fun session(syncId: String): SyncAggregateEnvelope? {
        val details = workoutDao.getSessionWithDetailsBySyncId(syncId) ?: return null
        val exercises = details.sortedExercises.map { item ->
            ExerciseSessionSyncDto(
                plannedOrder = item.exerciseSession.plannedOrder,
                executionOrder = item.exerciseSession.executionOrder,
                exerciseNameSnapshot = item.exerciseSession.exerciseNameSnapshot,
                plannedExercise = item.exerciseSession.plannedExerciseId?.let { exerciseRefById(it) },
                actualExercise = item.exerciseSession.actualExerciseId?.let { exerciseRefById(it) },
                machineLabelSnapshot = item.exerciseSession.machineLabelSnapshot,
                primaryMuscleSnapshot = item.exerciseSession.primaryMuscleSnapshot,
                restDurationSecondsSnapshot = item.exerciseSession.restDurationSecondsSnapshot,
                startedAt = item.exerciseSession.startedAt,
                finishedAt = item.exerciseSession.finishedAt,
                notes = item.exerciseSession.notes,
                replacementReason = item.exerciseSession.replacementReason,
                sets = item.sets.sortedBy { it.setNumber }.map { set ->
                    SetLogSyncDto(
                        setNumber = set.setNumber,
                        type = set.type,
                        weight = set.weight,
                        repetitions = set.repetitions,
                        completed = set.completed,
                        startedAt = set.startedAt,
                        finishedAt = set.finishedAt,
                        rpe = set.rpe,
                        rir = set.rir,
                        durationSeconds = set.durationSeconds
                    )
                }
            )
        }
        return envelope(
            SyncEntityType.WORKOUT_SESSION,
            syncId,
            WorkoutSessionSyncDto.SCHEMA_VERSION,
            WorkoutSessionSyncDto(
                syncId = details.session.syncId,
                templateSyncId = details.session.templateId?.let { workoutDao.getTemplateSyncId(it) },
                templateNameSnapshot = details.session.templateNameSnapshot,
                status = details.session.status,
                startedAt = details.session.startedAt,
                finishedAt = details.session.finishedAt,
                notes = details.session.notes,
                exercises = exercises
            )
        )
    }

    private suspend fun customExercise(syncId: String): SyncAggregateEnvelope? {
        val exercise = workoutDao.getExerciseBySyncId(syncId) ?: return null
        return envelope(
            SyncEntityType.CUSTOM_EXERCISE,
            syncId,
            CustomExerciseSyncDto.SCHEMA_VERSION,
            CustomExerciseSyncDto(
                syncId = syncId,
                name = exercise.name,
                primaryMuscle = exercise.primaryMuscle,
                equipment = exercise.equipment,
                description = exercise.description,
                isBodyweight = exercise.isBodyweight,
                rirEnabled = exercise.rirEnabled,
                active = exercise.active
            )
        )
    }

    private suspend fun bodyMeasurement(syncId: String): SyncAggregateEnvelope? {
        val measurement = bodyMeasurementDao.getMeasurementBySyncId(syncId) ?: return null
        return envelope(
            SyncEntityType.BODY_MEASUREMENT,
            syncId,
            BodyMeasurementSyncDto.SCHEMA_VERSION,
            BodyMeasurementSyncDto(
                syncId = measurement.syncId,
                date = measurement.date,
                createdAt = measurement.createdAt,
                weightKg = measurement.weightKg,
                heightCm = measurement.heightCm,
                bodyFatPercentage = measurement.bodyFatPercentage,
                waistCm = measurement.waistCm,
                abdomenCm = measurement.abdomenCm,
                chestCm = measurement.chestCm,
                leftArmCm = measurement.leftArmCm,
                rightArmCm = measurement.rightArmCm,
                leftThighCm = measurement.leftThighCm,
                rightThighCm = measurement.rightThighCm,
                calfCm = measurement.calfCm,
                hipCm = measurement.hipCm
            )
        )
    }

    private suspend fun checkIn(syncId: String): SyncAggregateEnvelope? {
        val checkIn = workoutDao.getCheckInBySyncId(syncId) ?: return null
        return envelope(
            SyncEntityType.CHECK_IN,
            syncId,
            CheckInSyncDto.SCHEMA_VERSION,
            CheckInSyncDto(
                syncId = checkIn.syncId,
                checkInTime = checkIn.checkInTime,
                checkOutTime = checkIn.checkOutTime,
                gymName = checkIn.gymName,
                sessionSyncId = checkIn.sessionId?.let { workoutDao.getSessionSyncId(it) }
            )
        )
    }

    private suspend fun exerciseRefById(exerciseId: Long): ExerciseRefDto? =
        workoutDao.getExerciseById(exerciseId)?.let { exerciseRef(it) }

    /**
     * A identidade global de um exercício: `canonicalId` para o catálogo, `syncId` para o que o
     * usuário criou.
     *
     * `null` quando a linha não tem nenhuma das duas — situação que só existiria em dado
     * inconsistente. Este montador **não** inventa identidade para "ser tolerante": preferir
     * enviar um agregado incompleto a falhar seria criar um dado errado no servidor de forma
     * permanente. Ele devolve `null`, e quem chama decide (a T16.6 reporta em vez de adivinhar).
     */
    private fun exerciseRef(exercise: ExerciseEntity): ExerciseRefDto? {
        val canonicalId = exercise.canonicalId?.takeIf { it.isNotBlank() }
        if (canonicalId != null && !exercise.isUserCreated) {
            return ExerciseRefDto(ExerciseRefDto.CANONICAL, canonicalId)
        }
        val syncId = exercise.syncId?.takeIf { it.isNotBlank() }
        if (syncId != null) return ExerciseRefDto(ExerciseRefDto.CUSTOM, syncId)
        return canonicalId?.let { ExerciseRefDto(ExerciseRefDto.CANONICAL, it) }
    }

    private inline fun <reified T> envelope(
        entityType: SyncEntityType,
        entitySyncId: String,
        schemaVersion: Int,
        payload: T
    ) = SyncAggregateEnvelope(
        entityType = entityType.name,
        entitySyncId = entitySyncId,
        schemaVersion = schemaVersion,
        payload = json.encodeToJsonElement(payload)
    )
}
