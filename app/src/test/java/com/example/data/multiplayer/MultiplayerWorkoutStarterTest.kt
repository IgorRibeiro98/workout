package com.example.data.multiplayer

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutExecutionMode
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import com.example.domain.multiplayer.FakeMultiplayerGateway
import com.example.domain.multiplayer.MultiplayerError
import com.example.domain.multiplayer.MultiplayerMemberRole
import com.example.domain.multiplayer.MultiplayerOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Como uma sala vira uma sessão **deste** aparelho (T19.5): o host com o próprio template, o
 * convidado com uma cópia independente do catálogo local — e nada quando o servidor recusa.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MultiplayerWorkoutStarterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var engine: WorkoutEngine
    private lateinit var repository: WorkoutRepository
    private lateinit var gateway: FakeMultiplayerGateway
    private lateinit var starter: MultiplayerWorkoutStarter
    private var templateId: Long = 0L
    private var programId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = database.workoutDao()
        val settings = SettingsManager(context)
        settings.setRestTimerState(null)
        engine = WorkoutEngine(dao, settings)
        repository = WorkoutRepository(dao, settingsManager = settings)
        gateway = FakeMultiplayerGateway()
        starter = MultiplayerWorkoutStarter(gateway, engine, repository, clientRequestIds = { "req-1" })

        programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        templateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A"))
        val exerciseId = dao.insertExercise(ExerciseEntity(name = "Supino", canonicalId = "supino-reto-barra"))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = 0, targetSets = 3))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `o host leva o treino portavel para a sala e inicia a propria sessao vinculada`() = runBlocking {
        val created = starter.createRoom(templateId, "social-peer") as MultiplayerStartResult.Started
        assertEquals("Treino A", created.room.workout.name)
        assertEquals(listOf("supino-reto-barra"), created.room.workout.exercises.map { it.canonicalExerciseId })

        val started = starter.startAsHost(created.room, templateId, "uid-a") as MultiplayerStartResult.Started
        assertTrue(!started.resumed)
        val session = dao.getActiveSession()!!
        assertEquals(WorkoutExecutionMode.DUO_REMOTE.name, session.executionMode)
        assertEquals(templateId, session.templateId)
        val link = dao.getMultiplayerLinkForSession(session.id)!!
        assertEquals(created.room.roomId, link.roomId)
        assertEquals("uid-a", link.accountUid)
        assertEquals(MultiplayerMemberRole.HOST.name, link.role)

        // Sem tabela de participante: a execução é a solo.
        assertTrue(dao.getSessionParticipants(session.id).isEmpty())

        // Iniciar de novo retoma a mesma sessão em vez de criar outra.
        val again = starter.startAsHost(created.room, templateId, "uid-a") as MultiplayerStartResult.Started
        assertTrue(again.resumed)
        assertEquals(session.id, dao.getActiveSession()!!.id)
    }

    @Test
    fun `um treino com exercicio personalizado nao vai para a sala`() = runBlocking {
        val custom = dao.insertExercise(ExerciseEntity(name = "Meu exercício", isUserCreated = true))
        val customTemplate = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Custom", shortIdentifier = "C"))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = customTemplate, exerciseId = custom, sortOrder = 0))

        val result = starter.createRoom(customTemplate, "social-peer")
        assertTrue(result is MultiplayerStartResult.Blocked)
        assertNull(dao.getActiveSession())
    }

    @Test
    fun `o convidado entra, ganha uma copia independente e inicia a propria sessao`() = runBlocking {
        gateway.seedRoom("room-x", myRole = MultiplayerMemberRole.GUEST, peerDisplayName = "Igor")
        val templatesBefore = dao.getTemplatesForProgramSync(programId).size

        val started = starter.joinAndStart("room-x", "uid-b") as MultiplayerStartResult.Started
        assertTrue(!started.resumed)

        val templates = dao.getTemplatesForProgramSync(programId)
        assertEquals(templatesBefore + 1, templates.size)
        val copy = templates.maxByOrNull { it.id }!!
        assertEquals("Treino A", copy.name)
        val copiedExercises = dao.getTemplateExercisesWithDetails(copy.id)
        assertEquals(listOf("supino-reto-barra"), copiedExercises.map { it.exercise.canonicalId })
        assertNull(copiedExercises.single().templateExercise.plannedWeight)

        val session = dao.getActiveSession()!!
        assertEquals(WorkoutExecutionMode.DUO_REMOTE.name, session.executionMode)
        assertEquals(copy.id, session.templateId)
        val link = dao.getMultiplayerLinkForSession(session.id)!!
        assertEquals("room-x", link.roomId)
        assertEquals("uid-b", link.accountUid)
        assertEquals(MultiplayerMemberRole.GUEST.name, link.role)
        assertEquals("Igor", link.peerDisplayName)

        // Entrar de novo (toque duplo / reabrir o convite) retoma: uma sessão, uma cópia.
        val again = starter.joinAndStart("room-x", "uid-b") as MultiplayerStartResult.Started
        assertTrue(again.resumed)
        assertEquals(templatesBefore + 1, dao.getTemplatesForProgramSync(programId).size)
        assertEquals(session.id, dao.getActiveSession()!!.id)
    }

    @Test
    fun `um exercicio fora do catalogo local recusa antes de escrever qualquer coisa`() = runBlocking {
        val room = gateway.seedRoom("room-y", myRole = MultiplayerMemberRole.GUEST)
        gateway.joinOverride = MultiplayerOutcome.Success(
            room.copy(workout = room.workout.copy(exercises = room.workout.exercises + com.example.domain.multiplayer.MultiplayerBlueprintExercise("inexistente", 1, 3, 8, 12, 60)))
        )
        val templatesBefore = dao.getTemplatesForProgramSync(programId).size

        val result = starter.joinAndStart("room-y", "uid-b")
        assertEquals(MultiplayerStartResult.MissingExercises(listOf("inexistente")), result)
        assertNull(dao.getActiveSession())
        assertEquals(templatesBefore, dao.getTemplatesForProgramSync(programId).size)
    }

    @Test
    fun `o servidor recusar o join nao deixa nada gravado`() = runBlocking {
        gateway.seedRoom("room-z", myRole = MultiplayerMemberRole.GUEST)
        gateway.joinOverride = MultiplayerOutcome.Failure(MultiplayerError.ROOM_CLOSED)
        val templatesBefore = dao.getTemplatesForProgramSync(programId).size

        val result = starter.joinAndStart("room-z", "uid-b")
        assertEquals(MultiplayerStartResult.Rejected(MultiplayerError.ROOM_CLOSED), result)
        assertNull(dao.getActiveSession())
        assertEquals(templatesBefore, dao.getTemplatesForProgramSync(programId).size)
    }

    @Test
    fun `com outro treino em andamento, entrar na sala e recusado`() = runBlocking {
        engine.startSession(templateId)
        gateway.seedRoom("room-w", myRole = MultiplayerMemberRole.GUEST)

        assertEquals(MultiplayerStartResult.WorkoutAlreadyInProgress, starter.joinAndStart("room-w", "uid-b"))
        assertEquals(WorkoutExecutionMode.SOLO.name, dao.getActiveSession()!!.executionMode)
        assertNotNull(dao.getActiveSession())
        assertEquals(SessionStatus.IN_PROGRESS.name, dao.getActiveSession()!!.status)
    }
}
