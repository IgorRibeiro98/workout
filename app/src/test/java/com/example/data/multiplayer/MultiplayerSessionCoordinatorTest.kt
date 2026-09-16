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
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.engine.WorkoutEngine
import com.example.domain.multiplayer.FakeMultiplayerGateway
import com.example.domain.multiplayer.MultiplayerConnection
import com.example.domain.multiplayer.MultiplayerEndReason
import com.example.domain.multiplayer.MultiplayerError
import com.example.domain.multiplayer.MultiplayerEventPayload
import com.example.domain.multiplayer.MultiplayerEventType
import com.example.domain.multiplayer.MultiplayerMemberRole
import com.example.domain.multiplayer.MultiplayerSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * O runtime da dupla à distância (T19.5), sobre o Room e o motor reais e um servidor em memória.
 *
 * O que está fixado: o evento sai do **estado** (concluir série no motor publica `SET_COMPLETED`,
 * sem peso); o peer é visto e nunca aplicado; rede fora deixa o treino intacto e os eventos
 * pendentes, e a volta da rede republica com dedupe; trocar de conta cancela o laço e não faz
 * chamada nenhuma pela conta nova; sala fechada e "sair" encerram só a coordenação; concluir e
 * cancelar avisam a sala.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MultiplayerSessionCoordinatorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var settings: SettingsManager
    private lateinit var engine: WorkoutEngine
    private lateinit var gateway: FakeMultiplayerGateway
    private lateinit var auth: FakeAuthGateway
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: MultiplayerSessionCoordinator
    private var templateId: Long = 0L

    private val accountA = SparkAccount(uid = "uid-a", displayName = "Igor", email = null, photoUrl = null)
    private val accountB = SparkAccount(uid = "uid-b", displayName = "Carla", email = null, photoUrl = null)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = database.workoutDao()
        settings = SettingsManager(context)
        settings.setRestTimerState(null)
        settings.setAutoRestTimerOnSet(false)
        engine = WorkoutEngine(dao, settings)
        gateway = FakeMultiplayerGateway()
        auth = FakeAuthGateway(initialAccount = accountA)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator = MultiplayerSessionCoordinator(
            engine = engine,
            dao = dao,
            gateway = gateway,
            authGateway = auth,
            scope = scope,
            pollWaitMs = 0L,
            reconnectBackoffMs = listOf(50L),
            minPollIntervalMs = 30L
        )

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        templateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A"))
        listOf("Supino" to "supino-reto-barra", "Remada" to "remada-curvada").forEachIndexed { index, (name, canonical) ->
            val exerciseId = dao.insertExercise(ExerciseEntity(name = name, canonicalId = canonical))
            dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = index, targetSets = 2))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private suspend fun startLinkedSession(roomId: String = "room-1", uid: String = accountA.uid) {
        gateway.seedRoom(roomId)
        engine.startSession(
            templateId = templateId,
            mode = WorkoutExecutionMode.DUO_REMOTE,
            multiplayer = WorkoutEngine.MultiplayerSessionLink(roomId, uid, MultiplayerMemberRole.HOST.name, "João")
        )
    }

    private suspend fun await(predicate: (MultiplayerSessionState?) -> Boolean): MultiplayerSessionState? =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { coordinator.state.first(predicate) }
        }

    private suspend fun awaitCondition(label: String, condition: suspend () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (!condition()) delay(20)
            }
        }
        assertTrue(label, condition())
    }

    private suspend fun completeFirstSet() {
        val details = dao.getSessionWithDetails(dao.getActiveSession()!!.id)!!
        val set = details.sortedExercises.first().sortedSets.first()
        engine.updateSet(set.copy(completed = true, weight = 80f, repetitions = 10, finishedAt = 5_000L))
    }

    @Test
    fun `conecta, publica o inicio, e a serie concluida no Room vira SET_COMPLETED sem peso`() = runBlocking {
        startLinkedSession()
        coordinator.start()

        val connected = await { it?.connection == MultiplayerConnection.CONNECTED }!!
        assertEquals("room-1", connected.roomId)
        assertEquals(MultiplayerMemberRole.HOST, connected.role)
        assertEquals("João", connected.peer?.displayName)

        awaitCondition("WORKOUT_STARTED publicado") {
            gateway.eventsOf("room-1").any { it.type == MultiplayerEventType.WORKOUT_STARTED }
        }

        completeFirstSet()

        awaitCondition("SET_COMPLETED publicado") {
            gateway.eventsOf("room-1").any { it.type == MultiplayerEventType.SET_COMPLETED }
        }
        val set = gateway.eventsOf("room-1").first { it.type == MultiplayerEventType.SET_COMPLETED }
        val payload = set.payload as MultiplayerEventPayload.SetCompleted
        assertEquals("supino-reto-barra", payload.canonicalExerciseId)
        assertEquals(1, payload.exercisePosition)
        assertEquals(1, payload.setNumber)
        assertEquals(2, payload.setCount)
        assertEquals(5_000L, payload.completedAt)
        // O peso e as repetições ficaram no Room, e só lá.
        assertFalse(gateway.publishedBatches.flatten().any { it.toString().contains("80") })
        assertEquals(0, await { it?.pendingLocalEvents == 0 }!!.pendingLocalEvents)
    }

    @Test
    fun `os eventos do peer viram progresso visto, e nada muda no Room`() = runBlocking {
        startLinkedSession()
        coordinator.start()
        await { it?.connection == MultiplayerConnection.CONNECTED }

        gateway.peerPublishes(
            "room-1",
            MultiplayerEventType.SET_COMPLETED,
            MultiplayerEventPayload.SetCompleted("supino-reto-barra", 1, 1, 2, 10L)
        )

        val seen = await { it?.peer?.progress?.completedSetCount == 1 }!!
        assertEquals(setOf(1), seen.peer!!.progress.exercises[1]?.completedSets)
        assertTrue(seen.peer!!.progress.started)

        // O Room deste aparelho não tem série concluída: o peer é visto, nunca aplicado.
        val details = dao.getSessionWithDetails(dao.getActiveSession()!!.id)!!
        assertTrue(details.exercises.flatMap { it.sets }.none { it.completed })
    }

    @Test
    fun `sem rede o treino continua, os eventos ficam pendentes, e a volta republica com dedupe`() = runBlocking {
        startLinkedSession()
        coordinator.start()
        await { it?.connection == MultiplayerConnection.CONNECTED }
        awaitCondition("início publicado") { gateway.eventsOf("room-1").isNotEmpty() }

        gateway.failing = true
        await { it?.connection == MultiplayerConnection.RECONNECTING }

        // O treino local continua: a série é gravada normalmente.
        completeFirstSet()
        val pending = await { (it?.pendingLocalEvents ?: 0) > 0 }!!
        assertEquals(MultiplayerConnection.RECONNECTING, pending.connection)
        assertEquals(SessionStatus.IN_PROGRESS.name, dao.getActiveSession()!!.status)
        assertTrue(dao.getSessionWithDetails(dao.getActiveSession()!!.id)!!.sortedExercises.first().sortedSets.first().completed)

        gateway.failing = false
        await { it?.connection == MultiplayerConnection.CONNECTED && it.pendingLocalEvents == 0 }

        // Reenvio integral depois da reconexão, e o servidor guardou cada fato uma vez só.
        val events = gateway.eventsOf("room-1")
        assertEquals(1, events.count { it.type == MultiplayerEventType.WORKOUT_STARTED })
        assertEquals(1, events.count { it.type == MultiplayerEventType.SET_COMPLETED })
        assertTrue(gateway.publishedBatches.size >= 2)
    }

    @Test
    fun `trocar de conta cancela o laco e a conta nova nao faz chamada nenhuma pela sala da anterior`() = runBlocking {
        startLinkedSession()
        coordinator.start()
        await { it?.connection == MultiplayerConnection.CONNECTED }
        gateway.holdPolls = true
        awaitCondition("um poll preso") { gateway.pollCalls.isNotEmpty() && gateway.holdPolls }

        auth.signOut()
        val signedOut = await { it == null || it.connection == MultiplayerConnection.ENDED }
        assertNull(signedOut)
        val pollsBeforeB = gateway.pollCalls.size
        val publishesBeforeB = gateway.publishedBatches.size

        auth.nextOutcome = AuthOutcome.Success(accountB)
        auth.signIn(context)
        val foreign = await { it?.endReason == MultiplayerEndReason.OTHER_ACCOUNT }!!
        assertEquals(MultiplayerConnection.ENDED, foreign.connection)
        assertNull(foreign.room)
        assertNull(foreign.peer)

        // Uma série concluída pela conta B **não** é publicada na sala de A.
        completeFirstSet()
        delay(300)
        assertEquals(pollsBeforeB, gateway.pollCalls.size)
        assertEquals(publishesBeforeB, gateway.publishedBatches.size)
        gateway.releasePoll()
        assertEquals(SessionStatus.IN_PROGRESS.name, dao.getActiveSession()!!.status)
    }

    @Test
    fun `sala encerrada acaba com a coordenacao, nao com o treino`() = runBlocking {
        startLinkedSession()
        coordinator.start()
        await { it?.connection == MultiplayerConnection.CONNECTED }

        gateway.closeRoom("room-1")
        val ended = await { it?.connection == MultiplayerConnection.ENDED }!!
        assertEquals(MultiplayerEndReason.ROOM_CLOSED, ended.endReason)

        completeFirstSet()
        delay(200)
        assertEquals(SessionStatus.IN_PROGRESS.name, dao.getActiveSession()!!.status)
        assertTrue(dao.getSessionWithDetails(dao.getActiveSession()!!.id)!!.sortedExercises.first().sortedSets.first().completed)
    }

    @Test
    fun `sair da sala e definitivo e o treino segue`() = runBlocking {
        startLinkedSession()
        coordinator.start()
        await { it?.connection == MultiplayerConnection.CONNECTED }

        coordinator.leaveRoom()
        val left = await { it?.endReason == MultiplayerEndReason.LEFT }!!
        assertEquals(MultiplayerConnection.ENDED, left.connection)
        assertEquals(listOf("room-1"), gateway.leaveCalls)
        val polls = gateway.pollCalls.size
        delay(200)
        assertEquals("nenhuma reconexão depois de sair", polls, gateway.pollCalls.size)
        assertNotNull(dao.getMultiplayerLinkForSession(dao.getActiveSession()!!.id)!!.finishedNotifiedAt)
        assertEquals(SessionStatus.IN_PROGRESS.name, dao.getActiveSession()!!.status)
        assertEquals(WorkoutExecutionMode.DUO_REMOTE.name, dao.getActiveSession()!!.executionMode)
    }

    @Test
    fun `concluir a sessao publica MEMBER_FINISHED, e a sala nao decide nada sobre a conclusao`() = runBlocking {
        startLinkedSession()
        coordinator.start()
        await { it?.connection == MultiplayerConnection.CONNECTED }
        val sessionId = dao.getActiveSession()!!.id

        engine.finishSession(sessionId)

        awaitCondition("MEMBER_FINISHED publicado") {
            gateway.eventsOf("room-1").any { it.type == MultiplayerEventType.MEMBER_FINISHED }
        }
        await { it == null }
        assertEquals(SessionStatus.COMPLETED.name, dao.getSessionById(sessionId)!!.status)
        awaitCondition("vínculo marcado como avisado") { dao.getMultiplayerLinkForSession(sessionId)?.finishedNotifiedAt != null }
    }

    @Test
    fun `cancelar a sessao sai da sala`() = runBlocking {
        startLinkedSession()
        coordinator.start()
        await { it?.connection == MultiplayerConnection.CONNECTED }
        val sessionId = dao.getActiveSession()!!.id

        engine.cancelSession(sessionId)

        awaitCondition("leave chamado") { gateway.leaveCalls.contains("room-1") }
        awaitCondition("vínculo avisado") { dao.getMultiplayerLinkForSession(sessionId)?.finishedNotifiedAt != null }
    }

    @Test
    fun `um aviso de conclusao que nao saiu e reenviado na proxima abertura`() = runBlocking {
        startLinkedSession()
        val sessionId = dao.getActiveSession()!!.id
        // Sem coordenador rodando: o processo "morreu" antes de avisar.
        engine.finishSession(sessionId)
        assertNull(dao.getMultiplayerLinkForSession(sessionId)!!.finishedNotifiedAt)

        gateway.failing = true
        gateway.failure = MultiplayerError.NETWORK
        coordinator.start()
        delay(200)
        assertNull("falha transitória não marca", dao.getMultiplayerLinkForSession(sessionId)!!.finishedNotifiedAt)

        gateway.failing = false
        // "Próxima abertura": um coordenador novo, como o processo reaberto faria.
        MultiplayerSessionCoordinator(engine, dao, gateway, auth, scope, pollWaitMs = 0L, minPollIntervalMs = 30L).start()
        awaitCondition("MEMBER_FINISHED reenviado") {
            gateway.eventsOf("room-1").any { it.type == MultiplayerEventType.MEMBER_FINISHED }
        }
        awaitCondition("vínculo avisado") { dao.getMultiplayerLinkForSession(sessionId)?.finishedNotifiedAt != null }
    }

    @Test
    fun `sessao solo nao toca o gateway`() = runBlocking {
        engine.startSession(templateId)
        coordinator.start()
        delay(300)
        assertNull(coordinator.state.value)
        assertTrue(gateway.pollCalls.isEmpty())
        assertTrue(gateway.publishedBatches.isEmpty())
    }
}
