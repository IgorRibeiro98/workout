package com.example.data.restore

import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.CheckInEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WeeklyGoalHistoryEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * O dataset que os testes de restore usam (T16.5).
 *
 * Ele não é "um treino e uma sessão": é o menor dataset que ainda exercita o que pode quebrar em
 * uma travessia entre dois aparelhos —
 *
 * - **ordem não trivial**: os exercícios de um treino estão em uma ordem diferente da ordem de
 *   inserção, e um deles é personalizado (a ordem tem que sobreviver, e a referência também);
 * - **histórico variado**: sessões com número diferente de exercícios e séries, cargas
 *   fracionárias, RIR, RPE, duração e uma série não concluída;
 * - **exercício trocado durante a execução**: `plannedExercise` ≠ `actualExercise`, com motivo;
 * - **referências que podem não resolver**: uma sessão citando um exercício que não existe mais;
 * - **todos os agregados do registry**, inclusive os três que só existem no backup.
 *
 * Os `syncId` são fixos e legíveis para que uma falha aponte o agregado, e não um UUID aleatório.
 */
object RestoreDatasetFixture {

    const val CANONICAL_SUPINO = "supino-reto-barra"
    const val CANONICAL_AGACHAMENTO = "agachamento-livre"
    const val CANONICAL_REMADA = "remada-curvada"

    const val PROGRAM_SYNC_ID = "11111111-1111-4111-8111-111111111111"
    const val TEMPLATE_A_SYNC_ID = "22222222-2222-4222-8222-222222222222"
    const val TEMPLATE_B_SYNC_ID = "33333333-3333-4333-8333-333333333333"
    const val CUSTOM_EXERCISE_SYNC_ID = "44444444-4444-4444-8444-444444444444"
    const val SESSION_1_SYNC_ID = "55555555-5555-4555-8555-555555555555"
    const val SESSION_2_SYNC_ID = "66666666-6666-4666-8666-666666666666"
    const val SESSION_3_SYNC_ID = "77777777-7777-4777-8777-777777777777"
    const val MEASUREMENT_SYNC_ID = "88888888-8888-4888-8888-888888888888"
    const val CHECK_IN_SYNC_ID = "99999999-9999-4999-8999-999999999999"

    /** Instala o catálogo canônico. Ele é conteúdo do app e existe nos dois aparelhos. */
    suspend fun seedCatalog(database: AppDatabase) {
        val dao = database.workoutDao()
        listOf(
            CANONICAL_SUPINO to "Supino reto",
            CANONICAL_AGACHAMENTO to "Agachamento livre",
            CANONICAL_REMADA to "Remada curvada"
        ).forEach { (canonicalId, name) ->
            dao.insertExercise(
                ExerciseEntity(
                    name = name,
                    canonicalId = canonicalId,
                    primaryMuscle = "Geral",
                    isUserCreated = false,
                    origin = "SYSTEM"
                )
            )
        }
    }

    /** Instala o dataset pessoal completo sobre o catálogo. */
    suspend fun seedPersonalData(database: AppDatabase) {
        val dao = database.workoutDao()

        val customExerciseId = dao.insertExercise(
            ExerciseEntity(
                name = "Rosca martelo no banco inclinado",
                primaryMuscle = "Bíceps",
                equipment = "Halteres",
                isUserCreated = true,
                origin = "USER",
                syncId = CUSTOM_EXERCISE_SYNC_ID
            )
        )
        val supinoId = dao.getExerciseByCanonicalId(CANONICAL_SUPINO)!!.id
        val agachamentoId = dao.getExerciseByCanonicalId(CANONICAL_AGACHAMENTO)!!.id
        val remadaId = dao.getExerciseByCanonicalId(CANONICAL_REMADA)!!.id

        val programId = dao.insertProgram(
            WorkoutProgramEntity(
                name = "Programa Hipertrofia",
                description = "Divisão de dois dias",
                isCurrent = true,
                syncId = PROGRAM_SYNC_ID
            )
        )

        val templateAId = dao.insertTemplate(
            WorkoutTemplateEntity(
                programId = programId,
                name = "Treino A",
                shortIdentifier = "A",
                orderInProgram = 0,
                dayOfWeek = "MONDAY",
                syncId = TEMPLATE_A_SYNC_ID
            )
        )
        // Ordem não trivial de propósito: o personalizado no meio, e a inserção fora da ordem final.
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateAId,
                exerciseId = remadaId,
                sortOrder = 2,
                targetSets = 3,
                minReps = 10,
                maxReps = 15,
                restDurationSeconds = 60,
                notes = "Puxar até o abdômen"
            )
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateAId,
                exerciseId = supinoId,
                sortOrder = 0,
                targetSets = 4,
                minReps = 8,
                maxReps = 12,
                restDurationSeconds = 90,
                plannedWeight = 62.5f,
                machineLabel = "Banco 3"
            )
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateAId,
                exerciseId = customExerciseId,
                sortOrder = 1,
                targetSets = 3,
                minReps = 12,
                maxReps = 15,
                restDurationSeconds = 45
            )
        )

        val templateBId = dao.insertTemplate(
            WorkoutTemplateEntity(
                programId = programId,
                name = "Treino B",
                shortIdentifier = "B",
                orderInProgram = 1,
                syncId = TEMPLATE_B_SYNC_ID
            )
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateBId,
                exerciseId = agachamentoId,
                sortOrder = 0,
                targetSets = 5,
                minReps = 5,
                maxReps = 8,
                restDurationSeconds = 180,
                plannedWeight = 100f
            )
        )

        // ---- histórico ----------------------------------------------------------------------

        val session1 = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateAId,
                startedAt = 1_690_000_000_000L,
                finishedAt = 1_690_003_600_000L,
                status = SessionStatus.COMPLETED.name,
                notes = "Treino puxado",
                templateNameSnapshot = "Treino A",
                syncId = SESSION_1_SYNC_ID
            )
        )
        val exerciseSession1 = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = session1,
                plannedExerciseId = supinoId,
                actualExerciseId = supinoId,
                exerciseNameSnapshot = "Supino reto",
                sortOrder = 0,
                plannedOrder = 0,
                executionOrder = 0,
                startedAt = 1_690_000_100_000L,
                finishedAt = 1_690_001_000_000L,
                machineLabelSnapshot = "Banco 3",
                primaryMuscleSnapshot = "Peito",
                restDurationSecondsSnapshot = 90
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = exerciseSession1,
                    setNumber = 1,
                    weight = 60f,
                    repetitions = 10,
                    completed = true,
                    startedAt = 1_690_000_200_000L,
                    finishedAt = 1_690_000_260_000L,
                    rir = 2
                ),
                SetLogEntity(
                    exerciseSessionId = exerciseSession1,
                    setNumber = 2,
                    weight = 62.5f,
                    repetitions = 8,
                    completed = true,
                    rpe = 8.5f,
                    durationSeconds = 42
                ),
                // Série não concluída: ela faz parte do que aconteceu e precisa voltar assim.
                SetLogEntity(
                    exerciseSessionId = exerciseSession1,
                    setNumber = 3,
                    weight = 62.5f,
                    repetitions = 0,
                    completed = false
                )
            )
        )
        val exerciseSession2 = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = session1,
                // Exercício trocado no meio do treino: planejado ≠ executado, com motivo.
                plannedExerciseId = customExerciseId,
                actualExerciseId = remadaId,
                exerciseNameSnapshot = "Remada curvada",
                sortOrder = 1,
                plannedOrder = 1,
                executionOrder = 1,
                replacementReason = "Aparelho ocupado",
                startedAt = 1_690_001_100_000L,
                finishedAt = 1_690_002_000_000L
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = exerciseSession2,
                    setNumber = 1,
                    type = "WARMUP",
                    weight = 20f,
                    repetitions = 15,
                    completed = true
                ),
                SetLogEntity(
                    exerciseSessionId = exerciseSession2,
                    setNumber = 2,
                    weight = 42.5f,
                    repetitions = 12,
                    completed = true,
                    rir = 1
                )
            )
        )

        val session2 = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateBId,
                startedAt = 1_691_000_000_000L,
                finishedAt = 1_691_004_000_000L,
                status = SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Treino B",
                syncId = SESSION_2_SYNC_ID
            )
        )
        val exerciseSession3 = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = session2,
                plannedExerciseId = agachamentoId,
                actualExerciseId = agachamentoId,
                exerciseNameSnapshot = "Agachamento livre",
                sortOrder = 0,
                plannedOrder = 0,
                executionOrder = 0
            )
        )
        dao.insertSetLogs(
            (1..5).map { number ->
                SetLogEntity(
                    exerciseSessionId = exerciseSession3,
                    setNumber = number,
                    weight = 100f + number,
                    repetitions = 6 - (number / 3),
                    completed = true
                )
            }
        )

        // Sessão sem template: histórico sobrevive ao treino que o originou.
        val session3 = dao.insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = 1_692_000_000_000L,
                finishedAt = 1_692_002_000_000L,
                status = SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Treino avulso",
                syncId = SESSION_3_SYNC_ID
            )
        )
        dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = session3,
                plannedExerciseId = null,
                actualExerciseId = null,
                exerciseNameSnapshot = "Alongamento",
                sortOrder = 0,
                plannedOrder = 0,
                executionOrder = 0
            )
        )

        dao.insertCheckIn(
            CheckInEntity(
                checkInTime = 1_690_000_000_000L,
                checkOutTime = 1_690_003_600_000L,
                gymName = "Academia do bairro",
                sessionId = session1,
                syncId = CHECK_IN_SYNC_ID
            )
        )

        dao.insertOrUpdateOverride(
            ExerciseUserOverrideEntity(
                exerciseId = supinoId,
                displayName = "Supino reto (barra olímpica)",
                notes = "Pegada média, pés firmes.",
                // Mídia local: ela existe aqui e **não** viaja no backup.
                customPhotoUri = "content://media/external/images/42",
                defaultRestSeconds = 75,
                updatedAt = 1_688_000_000_000L
            )
        )

        database.bodyMeasurementDao().insertMeasurement(
            BodyMeasurementEntity(
                date = 1_689_000_000_000L,
                createdAt = 1_689_000_100_000L,
                weightKg = 79.4f,
                heightCm = 178f,
                bodyFatPercentage = 18.5f,
                waistCm = 82f,
                syncId = MEASUREMENT_SYNC_ID
            )
        )

        database.weeklyGoalDao().insertGoal(
            WeeklyGoalHistoryEntity(
                effectiveFromWeekStartEpochDay = 20_700L,
                goal = 4,
                createdAt = 1_688_500_000_000L
            )
        )
    }
}

/**
 * A **impressão semântica** de um dataset — a base da comparação dos testes de ida e volta.
 *
 * Ela é a lista de agregados do snapshot canônico: identidade portátil, conteúdo, ordem e
 * relações. O que ela deliberadamente **não** contém é o que não deve sobreviver a uma travessia:
 * `localId`, o `deviceId` do aparelho de origem, o instante da captura e a versão do app.
 *
 * Comparar dois datasets por esta impressão é comparar o que o usuário tem — e não como o banco
 * numerou as linhas dele.
 */
suspend fun RestoreHarness.semanticFingerprint(): String {
    val document = Json.parseToJsonElement(canonicalSnapshotOfCurrentState()).jsonObject
    // Só os itens: `clientBackupId`, `deviceId`, `capturedAt` e `source` descrevem **a captura**,
    // não o dataset, e exigir que eles sejam iguais compararia a coisa errada.
    return requireNotNull(document["items"]) { "snapshot sem itens" }.toString()
}
