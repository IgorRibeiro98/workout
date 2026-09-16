package com.example.presentation.multiplayer

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutExecutionMode
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.multiplayer.MultiplayerWorkoutStarter
import com.example.data.repository.WorkoutRepository
import com.example.data.social.FakeFriendGateway
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.engine.WorkoutEngine
import com.example.domain.multiplayer.FakeMultiplayerGateway
import com.example.domain.multiplayer.MultiplayerInvitation
import com.example.domain.multiplayer.MultiplayerMemberRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * O lobby da dupla à distância (T19.5): convidar, aceitar, e nada da conta anterior sobreviver a
 * uma troca de conta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MultiplayerLobbyViewModelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeMultiplayerGateway
    private lateinit var friends: FakeFriendGateway
    private lateinit var auth: FakeAuthGateway
    private lateinit var starter: MultiplayerWorkoutStarter
    private lateinit var viewModel: MultiplayerLobbyViewModel
    private var templateId: Long = 0L
    private val stores = mutableListOf<ViewModelStore>()

    private val accountA = SparkAccount(uid = "uid-a", displayName = "Igor", email = null, photoUrl = null)
    private val accountC = SparkAccount(uid = "uid-c", displayName = "Carla", email = null, photoUrl = null)

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.workoutDao()
        val settings = SettingsManager(context)
        settings.setRestTimerState(null)
        val engine = WorkoutEngine(dao, settings)
        val repository = WorkoutRepository(dao, settingsManager = settings)
        gateway = FakeMultiplayerGateway()
        friends = FakeFriendGateway().apply {
            currentUid = accountA.uid
            register(FakeFriendGateway.Account("uid-a", "social-me", "SPK-AAAAAAAA", "Igor"))
            register(FakeFriendGateway.Account("uid-b", "social-peer", "SPK-BBBBBBBB", "João"))
            seedFriendship("uid-a", "uid-b")
        }
        auth = FakeAuthGateway(initialAccount = accountA)
        starter = MultiplayerWorkoutStarter(gateway, engine, repository, clientRequestIds = { "req-1" })

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        templateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A"))
        val exerciseId = dao.insertExercise(ExerciseEntity(name = "Supino", canonicalId = "supino-reto-barra"))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = 0, targetSets = 3))

        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): MultiplayerLobbyViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MultiplayerLobbyViewModel(gateway, starter, friends, auth, roomPollIntervalMs = 10L) as T
        }
        return ViewModelProvider(store, factory)[MultiplayerLobbyViewModel::class.java]
    }

    /**
     * Espera o estado com teto de tempo **real**: o starter escreve no Room, que responde pelo
     * executor dele e não pelo dispatcher de teste (memória `t178-checkins-feed`).
     */
    private suspend fun awaitUi(predicate: (MultiplayerLobbyUiState) -> Boolean): MultiplayerLobbyUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { viewModel.uiState.first(predicate) } }

    @Test
    fun `carrega amigos e convites da conta atual`() = runTest(dispatcher) {
        gateway.invitations = listOf(MultiplayerInvitation("room-9", 1L, 2L, "social-peer", "João", "Pernas", 4))
        advanceUntilIdle()

        val state = awaitUi { !it.isLoadingFriends && !it.isLoadingInvitations && it.friends.isNotEmpty() }
        assertTrue(state.isSignedIn)
        assertEquals(listOf("João"), state.friends.map { it.displayName })
        assertEquals(listOf("room-9"), state.invitations.map { it.roomId })
        assertFalse(state.isLoadingFriends)
        assertFalse(state.isLoadingInvitations)
    }

    @Test
    fun `convidar cria a sala WAITING, e iniciar como host abre a sessao vinculada`() = runTest(dispatcher) {
        advanceUntilIdle()
        val events = mutableListOf<MultiplayerLobbyEvent>()
        backgroundScope.launch { viewModel.events.collect { events += it } }

        viewModel.createRoom(templateId, "social-peer")
        val created = awaitUi { it.createdRoom != null }
        assertNotNull(created.createdRoom)
        assertFalse(created.peerHasJoined)

        viewModel.startAsHost(templateId)
        val started = awaitUi { !it.isStarting && it.createdRoom == null }
        assertEquals(listOf(MultiplayerLobbyEvent.WorkoutStarted), events)
        assertNull(started.createdRoom)

        val session = database.workoutDao().getActiveSession()!!
        assertEquals(WorkoutExecutionMode.DUO_REMOTE.name, session.executionMode)
        assertEquals(MultiplayerMemberRole.HOST.name, database.workoutDao().getMultiplayerLinkForSession(session.id)!!.role)
    }

    @Test
    fun `aceitar um convite cria a copia e inicia, e um toque duplo nao inicia duas vezes`() = runTest(dispatcher) {
        gateway.seedRoom("room-x", myRole = MultiplayerMemberRole.GUEST, peerDisplayName = "Igor")
        advanceUntilIdle()
        val events = mutableListOf<MultiplayerLobbyEvent>()
        backgroundScope.launch { viewModel.events.collect { events += it } }

        viewModel.joinAndStart("room-x")
        viewModel.joinAndStart("room-x")
        awaitUi { !it.isStarting }
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (events.isEmpty()) kotlinx.coroutines.delay(10) } }

        assertEquals(1, events.size)
        val session = database.workoutDao().getActiveSession()!!
        assertEquals(WorkoutExecutionMode.DUO_REMOTE.name, session.executionMode)
        assertEquals("room-x", database.workoutDao().getMultiplayerLinkForSession(session.id)!!.roomId)
    }

    @Test
    fun `trocar de conta zera a tela e a sala da conta anterior nao reaparece`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.createRoom(templateId, "social-peer")
        assertNotNull(awaitUi { it.createdRoom != null }.createdRoom)

        auth.signOut()
        val signedOut = awaitUi { !it.isSignedIn }
        assertNull(signedOut.createdRoom)
        assertTrue(signedOut.friends.isEmpty())

        friends.currentUid = accountC.uid
        auth.nextOutcome = AuthOutcome.Success(accountC)
        auth.signIn(context)
        val state = awaitUi { it.isSignedIn && !it.isLoadingFriends }
        assertNull("a sala de A não é de C", state.createdRoom)
        assertTrue(state.friends.isEmpty())
    }

    @Test
    fun `sem backend configurado a tela diz isso e nao chama nada`() = runTest(dispatcher) {
        gateway = FakeMultiplayerGateway(isConfigured = false)
        viewModel = newViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isConfigured)
        assertTrue(gateway.pollCalls.isEmpty())
    }
}
