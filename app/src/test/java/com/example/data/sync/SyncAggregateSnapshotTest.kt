package com.example.data.sync

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.dto.ExerciseRefDto
import com.example.data.sync.dto.WorkoutSessionSyncDto
import com.example.data.sync.dto.WorkoutTemplateSyncDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O contrato de serialização dos agregados (T16.3).
 *
 * O ponto destes testes é provar duas coisas que a T16.4 vai depender:
 *
 * 1. dá para montar o conteúdo de um agregado **a partir da identidade global**, o que justifica a
 *    Outbox guardar referência em vez de snapshot congelado;
 * 2. o que sai não é a entidade do Room disfarçada: nenhum `localId` atravessa o contrato, a ordem
 *    é explícita e cada payload carrega a própria versão de schema.
 *
 * Nada aqui envia nada.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncAggregateSnapshotTest {

    private lateinit var database: AppDatabase
    private lateinit var builder: SyncAggregateSnapshotBuilder
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        builder = SyncAggregateSnapshotBuilder(database.workoutDao(), database.bodyMeasurementDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun templateSnapshotKeepsOrderAndReferencesExercisesByGlobalIdentity() = runTest {
        val dao = database.workoutDao()
        val program = WorkoutProgramEntity(name = "Programa")
        val programId = dao.insertProgram(program)
        val template = WorkoutTemplateEntity(
            programId = programId,
            name = "Peito + Costas",
            shortIdentifier = "B",
            orderInProgram = 1,
            dayOfWeek = "MONDAY"
        )
        val templateId = dao.insertTemplate(template)

        val canonicalId = dao.insertExercise(
            ExerciseEntity(name = "Supino Reto", canonicalId = "canonical.supino", isUserCreated = false)
        )
        val customExercise = ExerciseEntity(
            name = "Rosca da academia do bairro",
            isUserCreated = true,
            syncId = SyncIds.random()
        )
        val customId = dao.insertExercise(customExercise)

        // Ordem inserida ao contrário da ordem de domínio, de propósito.
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateId, exerciseId = customId, sortOrder = 20, targetSets = 3
            )
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateId, exerciseId = canonicalId, sortOrder = 10,
                targetSets = 4, minReps = 6, maxReps = 10, restDurationSeconds = 120,
                plannedWeight = 100f, machineLabel = "Banco 2", notes = "pegada média"
            )
        )

        val envelope = builder.snapshot(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)!!
        assertEquals(SyncEntityType.WORKOUT_TEMPLATE.name, envelope.entityType)
        assertEquals(template.syncId, envelope.entitySyncId)
        assertEquals(WorkoutTemplateSyncDto.SCHEMA_VERSION, envelope.schemaVersion)

        val dto = Json.decodeFromJsonElement(WorkoutTemplateSyncDto.serializer(), envelope.payload)
        assertEquals(template.syncId, dto.syncId)
        assertEquals(program.syncId, dto.programSyncId)
        assertEquals("Peito + Costas", dto.name)
        assertEquals(1, dto.orderInProgram)

        // Ordem preservada e explícita: o canônico (sortOrder 10) vem antes do personalizado (20).
        assertEquals(listOf(0, 1), dto.exercises.map { it.position })
        assertEquals(
            listOf(
                ExerciseRefDto(ExerciseRefDto.CANONICAL, "canonical.supino"),
                ExerciseRefDto(ExerciseRefDto.CUSTOM, customExercise.syncId!!)
            ),
            dto.exercises.map { it.exercise }
        )
        val first = dto.exercises.first()
        assertEquals(4, first.targetSets)
        assertEquals(120, first.restDurationSeconds)
        assertEquals(100f, first.plannedWeight)
        assertEquals("Banco 2", first.machineLabel)

        // O contrato não é a entidade do Room disfarçada: nenhum identificador local atravessa.
        assertFalse(envelope.payload.jsonObject.keys.contains("id"))
        assertFalse(envelope.payload.jsonObject.keys.contains("programId"))
        val serialized = Json.encodeToString(WorkoutTemplateSyncDto.serializer(), dto)
        // `ExerciseRefDto.id` é identidade global (canonicalId ou syncId) e é legítimo; os
        // proibidos são os identificadores de linha do Room.
        listOf("\"programId\"", "\"templateId\"", "\"exerciseId\"").forEach { field ->
            assertFalse("nenhum localId pode atravessar o contrato: $field", serialized.contains(field))
        }
    }

    @Test
    fun completedSessionSnapshotPreservesHistoryExactly() = runTest {
        val dao = database.workoutDao()
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        val template = WorkoutTemplateEntity(programId = programId, name = "Peito")
        val templateId = dao.insertTemplate(template)
        val exerciseId = dao.insertExercise(
            ExerciseEntity(name = "Supino Reto", canonicalId = "canonical.supino")
        )

        val session = WorkoutSessionEntity(
            templateId = templateId,
            startedAt = 1_000L,
            finishedAt = 2_000L,
            status = SessionStatus.COMPLETED.name,
            notes = "Treino bom",
            templateNameSnapshot = "Peito"
        )
        val sessionId = dao.insertSession(session)
        val exerciseSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exerciseId,
                actualExerciseId = exerciseId,
                exerciseNameSnapshot = "Supino Reto (como estava naquele dia)",
                sortOrder = 1,
                plannedOrder = 1,
                executionOrder = 1
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 2, weight = 100f, repetitions = 7, completed = true),
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 1, weight = 100f, repetitions = 8, completed = true)
            )
        )

        val envelope = builder.snapshot(SyncEntityType.WORKOUT_SESSION, session.syncId)!!
        assertEquals(WorkoutSessionSyncDto.SCHEMA_VERSION, envelope.schemaVersion)
        val dto = Json.decodeFromJsonElement(WorkoutSessionSyncDto.serializer(), envelope.payload)

        assertEquals(session.syncId, dto.syncId)
        assertEquals(template.syncId, dto.templateSyncId)
        assertEquals(SessionStatus.COMPLETED.name, dto.status)
        assertEquals(1_000L, dto.startedAt)
        assertEquals(2_000L, dto.finishedAt)

        val exercise = dto.exercises.single()
        // O nome histórico vai no snapshot: reconstruir pelo nome atual do exercício reescreveria
        // o passado quando o usuário renomeasse alguma coisa.
        assertEquals("Supino Reto (como estava naquele dia)", exercise.exerciseNameSnapshot)
        assertEquals(ExerciseRefDto(ExerciseRefDto.CANONICAL, "canonical.supino"), exercise.actualExercise)
        assertEquals(listOf(1, 2), exercise.sets.map { it.setNumber })
        assertEquals(listOf(8, 7), exercise.sets.map { it.repetitions })
        assertTrue(exercise.sets.all { it.completed })
    }

    @Test
    fun snapshotOfAnEntityThatNoLongerExistsIsNull() = runTest {
        assertNull(builder.snapshot(SyncEntityType.WORKOUT_TEMPLATE, SyncIds.random()))
        assertNull(builder.snapshot(SyncEntityType.WORKOUT_SESSION, SyncIds.random()))
        assertNull(builder.snapshot(SyncEntityType.BODY_MEASUREMENT, SyncIds.random()))
    }

    @Test
    fun everyAggregateTypeCanBeSerialized() = runTest {
        val dao = database.workoutDao()
        val program = WorkoutProgramEntity(name = "Programa")
        val programId = dao.insertProgram(program)
        val template = WorkoutTemplateEntity(programId = programId, name = "Treino A")
        dao.insertTemplate(template)
        val session = WorkoutSessionEntity(templateId = null, startedAt = 1L, status = SessionStatus.COMPLETED.name)
        val sessionId = dao.insertSession(session)
        val custom = ExerciseEntity(name = "Custom", isUserCreated = true, syncId = SyncIds.random())
        dao.insertExercise(custom)
        val checkIn = com.example.data.local.CheckInEntity(checkInTime = 5L, sessionId = sessionId)
        dao.insertCheckIn(checkIn)
        val measurement = BodyMeasurementEntity(date = 10L, weightKg = 80f)
        database.bodyMeasurementDao().insertMeasurement(measurement)

        val expected = mapOf(
            SyncEntityType.WORKOUT_PROGRAM to program.syncId,
            SyncEntityType.WORKOUT_TEMPLATE to template.syncId,
            SyncEntityType.WORKOUT_SESSION to session.syncId,
            SyncEntityType.CUSTOM_EXERCISE to custom.syncId!!,
            SyncEntityType.CHECK_IN to checkIn.syncId,
            SyncEntityType.BODY_MEASUREMENT to measurement.syncId
        )
        // Todo tipo declarado precisa ter serializador: um agregado sem contrato viraria uma
        // entrada de Outbox que ninguém consegue enviar.
        assertEquals(SyncEntityType.entries.toSet(), expected.keys)
        expected.forEach { (type, syncId) ->
            val envelope = builder.snapshot(type, syncId)
            assertNotNull("faltou snapshot para $type", envelope)
            assertEquals(type.name, envelope!!.entityType)
            assertEquals(syncId, envelope.entitySyncId)
            assertTrue("payload de $type precisa de versão", envelope.schemaVersion >= 1)
        }

        // O check-in referencia a sessão por identidade global, nunca pelo id local.
        val checkInPayload = builder.snapshot(SyncEntityType.CHECK_IN, checkIn.syncId)!!.payload.jsonObject
        assertEquals(session.syncId, checkInPayload["sessionSyncId"].toString().trim('"'))
    }
}
