package com.example.domain.engine

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.PRType
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutExecutionMode
import com.example.data.local.WorkoutParticipantRole
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.SyncAggregateSnapshotBuilder
import com.example.data.sync.SyncEntityType
import com.example.domain.workout.execution.DuoExecution
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * O motor em modo dupla local (T19.4), sobre banco real.
 *
 * O que está fixado aqui são os invariantes que a tarefa declara bloqueantes: uma única sessão,
 * do dono; convidado sem histórico, sem PR e fora do sync; descansos independentes por timestamp;
 * o treino só acaba quando os dois acabaram; e o solo intocado — sem participante, sem espelho.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutEngineDuoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var settings: SettingsManager
    private lateinit var engine: WorkoutEngine

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.workoutDao()
        settings = SettingsManager(context)
        settings.setDefaultRestSeconds(90)
        settings.setDefaultExerciseRestSeconds(120)
        settings.setAutoRestTimerOnSet(true)
        settings.setRestTimerState(null)
        engine = WorkoutEngine(dao, settings)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedTemplate(exerciseNames: List<String> = listOf("Supino reto"), targetSets: Int = 2, restSeconds: Int = 45): Pair<Long, List<Long>> {
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A")
        )
        val exerciseIds = exerciseNames.mapIndexed { index, name ->
            val exerciseId = dao.insertExercise(ExerciseEntity(name = name))
            dao.insertTemplateExercise(
                WorkoutTemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = exerciseId,
                    sortOrder = index,
                    targetSets = targetSets,
                    restDurationSeconds = restSeconds
                )
            )
            exerciseId
        }
        return templateId to exerciseIds
    }

    private suspend fun startDuo(templateId: Long, guestName: String = "João"): WorkoutSessionEntity {
        engine.startSession(templateId, WorkoutExecutionMode.DUO_LOCAL, guestName)
        return dao.getActiveSession()!!
    }

    private suspend fun duo(): DuoExecution = engine.activeDuoExecutionFlow.first()!!

    private fun countSessions(status: String): Int = database.query(
        "SELECT COUNT(*) FROM workout_sessions WHERE status = ?",
        arrayOf(status)
    ).use { it.moveToFirst(); it.getInt(0) }

    // ---------------------------------------------------------------------------------------
    // Início
    // ---------------------------------------------------------------------------------------

    @Test
    fun `iniciar em dupla cria uma sessao do dono com dois participantes e o espelho do convidado`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 3)

        val session = startDuo(templateId, guestName = "  João  ")

        assertEquals(WorkoutExecutionMode.DUO_LOCAL.name, session.executionMode)
        assertEquals(1, countSessions(SessionStatus.IN_PROGRESS.name))

        val participants = dao.getSessionParticipants(session.id)
        assertEquals(listOf("OWNER", "GUEST"), participants.map { it.role })
        assertEquals(listOf(0, 1), participants.map { it.position })
        assertNull("o dono não tem nome local", participants[0].displayName)
        assertEquals("João", participants[1].displayName)

        val duo = duo()
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()
        assertEquals(3, exercise.sets.size)
        assertEquals(3, duo.guestSetsFor(exercise.exerciseSession.id).size)
        assertEquals(listOf(1, 2, 3), duo.guestSetsFor(exercise.exerciseSession.id).map { it.setNumber })
    }

    @Test
    fun `iniciar solo continua sem participante e sem espelho`() = runBlocking {
        val (templateId, _) = seedTemplate()

        engine.startSession(templateId)
        val session = dao.getActiveSession()!!

        assertEquals(WorkoutExecutionMode.SOLO.name, session.executionMode)
        assertTrue(dao.getSessionParticipants(session.id).isEmpty())
        assertNull(engine.activeDuoExecutionFlow.first())
        assertEquals(0, dao.countIncompleteGuestSetsForSession(session.id))
    }

    @Test
    fun `dupla sem nome de convidado e recusada e nao cria sessao`() = runBlocking {
        val (templateId, _) = seedTemplate()

        val result = runCatching { engine.startSession(templateId, WorkoutExecutionMode.DUO_LOCAL, "   ") }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertNull(dao.getActiveSession())
    }

    @Test
    fun `dois inicios concorrentes em dupla criam uma unica sessao`() = runBlocking {
        val (templateId, _) = seedTemplate()

        listOf(
            async { engine.startSession(templateId, WorkoutExecutionMode.DUO_LOCAL, "João") },
            async { engine.startSession(templateId, WorkoutExecutionMode.DUO_LOCAL, "João") }
        ).awaitAll()

        assertEquals(1, countSessions(SessionStatus.IN_PROGRESS.name))
        val session = dao.getActiveSession()!!
        assertEquals(2, dao.getSessionParticipants(session.id).size)
    }

    // ---------------------------------------------------------------------------------------
    // Alternância e descansos
    // ---------------------------------------------------------------------------------------

    @Test
    fun `serie do dono inicia o descanso do dono e nao o do convidado`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2, restSeconds = 45)
        val session = startDuo(templateId)
        val sets = dao.getSessionWithDetails(session.id)!!.exercises.single().sortedSets

        engine.updateSet(sets[0].copy(completed = true))

        val ownerTarget = engine.restTimerTarget.first()
        assertNotNull("o dono descansa entre séries", ownerTarget)
        assertEquals("REST_SET", settings.restTimerTypeFlow.first())
        assertNull("o convidado ainda não fez nada", duo().guest.restEndsAt)
        // O treino não acabou: o convidado tem as duas séries.
        assertEquals(2, dao.countIncompleteGuestSetsForSession(session.id))
    }

    @Test
    fun `serie do convidado inicia o descanso do convidado sem mexer no do dono`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2, restSeconds = 45)
        val session = startDuo(templateId)
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()

        engine.updateSet(exercise.sortedSets[0].copy(completed = true))
        val ownerTargetBefore = engine.restTimerTarget.first()!!

        val guestSet1 = duo().guestSetsFor(exercise.exerciseSession.id).first()
        engine.completeGuestSet(guestSet1.copy(weight = 40f, repetitions = 12))

        val duoAfter = duo()
        val guestRest = duoAfter.guest.restEndsAt
        assertNotNull("o convidado descansa entre séries", guestRest)
        val guestSeconds = ((guestRest!! - System.currentTimeMillis()) / 1000).toInt()
        assertTrue("esperado ~45 s para o convidado, veio $guestSeconds", guestSeconds in 40..46)
        assertEquals("o relógio do dono não foi tocado", ownerTargetBefore, engine.restTimerTarget.first())

        val persisted = duoAfter.guestSetsFor(exercise.exerciseSession.id).first()
        assertTrue(persisted.completed)
        assertNotNull(persisted.finishedAt)
        assertEquals(40f, persisted.weight, 0.01f)
        assertEquals(12, persisted.repetitions)
        // A série do dono continua com os valores dele: nada do convidado entrou em `set_logs`.
        val ownerSets = dao.getSessionWithDetails(session.id)!!.exercises.single().sortedSets
        assertEquals(exercise.sortedSets[0].weight, ownerSets[0].weight, 0.01f)
        assertFalse(ownerSets[1].completed)
    }

    @Test
    fun `ultima serie do convidado no exercicio usa o descanso entre exercicios`() = runBlocking {
        val (templateId, _) = seedTemplate(exerciseNames = listOf("Supino", "Remada"), targetSets = 1, restSeconds = 45)
        val session = startDuo(templateId)
        val first = dao.getSessionWithDetails(session.id)!!.sortedExercises.first()

        engine.updateSet(first.sortedSets[0].copy(completed = true))
        engine.completeGuestSet(duo().guestSetsFor(first.exerciseSession.id).single())

        val guestSeconds = ((duo().guest.restEndsAt!! - System.currentTimeMillis()) / 1000).toInt()
        assertTrue("esperado ~120 s (entre exercícios), veio $guestSeconds", guestSeconds in 115..121)
    }

    @Test
    fun `estender e pular o descanso do convidado seguem a regra do dono`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2)
        val session = startDuo(templateId)
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()
        val guestId = duo().guest.id

        assertEquals("sem descanso não se inventa um", WorkoutEngine.NO_ACTIVE_REST_TIMER, engine.adjustGuestRest(guestId, 30))

        engine.updateSet(exercise.sortedSets[0].copy(completed = true))
        engine.completeGuestSet(duo().guestSetsFor(exercise.exerciseSession.id).first())
        val before = duo().guest.restEndsAt!!

        val after = engine.adjustGuestRest(guestId, 30)
        assertTrue("o alvo precisa avançar ~30 s", after - before in 29_000..31_000)
        assertEquals(after, duo().guest.restEndsAt)

        engine.skipGuestRest(guestId)
        assertNull(duo().guest.restEndsAt)
    }

    @Test
    fun `o treino so acaba quando os dois acabaram`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 1)
        val session = startDuo(templateId)
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()

        // O dono concluiu a única série dele: em solo o treino acabaria e nenhum descanso abriria.
        engine.updateSet(exercise.sortedSets[0].copy(completed = true))
        assertNotNull("na dupla o convidado ainda vem: o dono descansa", engine.restTimerTarget.first())

        // O convidado conclui a dele: agora acabou, e nenhum relógio fica correndo.
        engine.completeGuestSet(duo().guestSetsFor(exercise.exerciseSession.id).single())
        assertNull(engine.restTimerTarget.first())
        assertNull(duo().guest.restEndsAt)
    }

    @Test
    fun `serie adicionada ou removida pelo dono e espelhada para o convidado`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2)
        val session = startDuo(templateId)
        val exerciseSessionId = dao.getSessionWithDetails(session.id)!!.exercises.single().exerciseSession.id

        engine.addSet(exerciseSessionId, setNumber = 3, repetitions = 8, weight = 50f)
        assertEquals(listOf(1, 2, 3), duo().guestSetsFor(exerciseSessionId).map { it.setNumber })

        val ownerSet3 = dao.getSetLogsForExerciseSession(exerciseSessionId).single { it.setNumber == 3 }
        engine.removeSet(ownerSet3)
        assertEquals(listOf(1, 2), duo().guestSetsFor(exerciseSessionId).map { it.setNumber })
    }

    // ---------------------------------------------------------------------------------------
    // Finalização: uma sessão, do dono; o convidado fora de PR, histórico e sync
    // ---------------------------------------------------------------------------------------

    @Test
    fun `finalizar gera uma unica sessao concluida e o PR ignora as series do convidado`() = runBlocking {
        val (templateId, exerciseIds) = seedTemplate(targetSets = 1)
        val session = startDuo(templateId)
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()

        engine.updateSet(exercise.sortedSets[0].copy(completed = true, weight = 60f, repetitions = 10))
        // O convidado é mais forte: 100 kg. Isso nunca pode virar recorde do dono.
        engine.completeGuestSet(duo().guestSetsFor(exercise.exerciseSession.id).single().copy(weight = 100f, repetitions = 10))
        engine.finishSession(session.id)

        assertEquals(1, countSessions(SessionStatus.COMPLETED.name))
        assertEquals(0, countSessions(SessionStatus.IN_PROGRESS.name))
        val record = dao.getHighestPR(exerciseIds.single(), PRType.MAX_WEIGHT.name)
        assertEquals(60f, record!!.value, 0.01f)

        // O resumo (histórico) tem só a série do dono.
        val summary = dao.getCompletedSessionSummaryById(session.id)!!
        assertEquals(1, summary.exercises.single().sets.size)
        assertEquals(60f, summary.exercises.single().sets.single().weight, 0.01f)
    }

    @Test
    fun `o agregado de sync da sessao nao carrega nada do convidado`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 1)
        val session = startDuo(templateId, guestName = "Convidado Secreto")
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()

        engine.updateSet(exercise.sortedSets[0].copy(completed = true, weight = 60f))
        engine.completeGuestSet(duo().guestSetsFor(exercise.exerciseSession.id).single().copy(weight = 33.5f, repetitions = 7))
        engine.finishSession(session.id)

        val envelope = SyncAggregateSnapshotBuilder(dao, database.bodyMeasurementDao())
            .snapshot(SyncEntityType.WORKOUT_SESSION, session.syncId)!!
        val payload = envelope.payload.toString()
        assertFalse("o nome do convidado não sai do aparelho", payload.contains("Convidado Secreto"))
        assertFalse("a carga do convidado não sai do aparelho", payload.contains("33.5"))
        assertFalse("o modo de execução não faz parte do contrato", payload.contains("executionMode"))
        assertTrue(payload.contains("60.0"))
    }

    @Test
    fun `sessao concluida nao recebe serie nem do dono nem do convidado`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2)
        val session = startDuo(templateId)
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()
        val guestSet2 = duo().guestSetsFor(exercise.exerciseSession.id)[1]

        engine.finishSession(session.id)

        engine.updateSet(exercise.sortedSets[1].copy(completed = true))
        engine.completeGuestSet(guestSet2)

        val after = dao.getSessionWithDetails(session.id)!!.exercises.single().sortedSets
        assertFalse("um toque atrasado não reescreve histórico", after[1].completed)
        assertFalse(dao.getGuestSetLogById(guestSet2.id)!!.completed)
        assertNull(engine.restTimerTarget.first())
    }

    @Test
    fun `cancelar segue a regra atual e limpa o descanso do convidado`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2)
        val session = startDuo(templateId)
        val exercise = dao.getSessionWithDetails(session.id)!!.exercises.single()
        engine.updateSet(exercise.sortedSets[0].copy(completed = true))
        engine.completeGuestSet(duo().guestSetsFor(exercise.exerciseSession.id).first())
        val guestId = dao.getSessionParticipants(session.id).single { it.role == WorkoutParticipantRole.GUEST.name }.id

        engine.cancelSession(session.id)

        assertEquals(SessionStatus.CANCELLED.name, dao.getSessionById(session.id)!!.status)
        assertNull(dao.getSessionParticipantById(guestId)!!.restEndsAt)
        assertNull(engine.restTimerTarget.first())
        assertNull(engine.activeDuoExecutionFlow.first())
    }

    @Test
    fun `apagar a sessao leva participantes e espelho junto`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 1)
        val session = startDuo(templateId)
        engine.finishSession(session.id)

        engine.deleteHistoricalSession(dao.getSessionById(session.id)!!)

        assertTrue(dao.getSessionParticipants(session.id).isEmpty())
        val orphans = database.query("SELECT COUNT(*) FROM workout_guest_set_logs", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, orphans)
    }
}
