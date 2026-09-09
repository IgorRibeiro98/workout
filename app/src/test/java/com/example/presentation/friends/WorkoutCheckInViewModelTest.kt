package com.example.presentation.friends

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutSessionEntity
import com.example.data.repository.CHECKIN_LOCAL_WINDOW_MS
import com.example.data.repository.CheckInEligibility
import com.example.data.repository.WorkoutCheckInPublisher
import com.example.data.sync.SyncOutcome
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SocialCheckInAuthor
import com.example.domain.social.SocialDiscoverability
import com.example.domain.social.SocialError
import com.example.domain.social.SocialGateway
import com.example.domain.social.SocialOutcome
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.StubWorkoutCheckInGateway
import com.example.domain.social.UploadedCheckInMedia
import com.example.domain.social.WorkoutCheckInOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O CTA de check-in do Resumo e do Histórico (T17.8 §143/§144/§150).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutCheckInViewModelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeCheckInGateway
    private lateinit var socialGateway: FakeSocialGateway
    private lateinit var authGateway: FakeAuthGateway
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var viewModel: WorkoutCheckInViewModel

    private val now = 1_800_000_000_000L

    private class FakeCheckInGateway : StubWorkoutCheckInGateway() {
        var createCalls: Int = 0
        var lastRequestIds = mutableListOf<String>()
        var createResult: WorkoutCheckInOutcome<WorkoutCheckIn> = WorkoutCheckInOutcome.Success(
            WorkoutCheckIn("checkin-1", SocialCheckInAuthor("s", "Alice"), 1_800_000_000_000L, isCurrentUser = true)
        )

        /** O conteúdo de cada `POST` (T17.9): legenda e `mediaId`. */
        val createdContent = mutableListOf<Pair<String?, String?>>()

        /** As respostas de upload, na ordem. A última se repete. */
        var uploadResults: MutableList<WorkoutCheckInOutcome<UploadedCheckInMedia>> = mutableListOf()
        val uploadedIds = mutableListOf<String>()

        override suspend fun createCheckIn(
            sessionSyncId: String,
            clientRequestId: String,
            caption: String?,
            mediaId: String?
        ): WorkoutCheckInOutcome<WorkoutCheckIn> {
            createCalls++
            lastRequestIds += clientRequestId
            createdContent += caption to mediaId
            return createResult
        }

        override suspend fun uploadMedia(
            sessionSyncId: String,
            clientUploadId: String,
            bytes: ByteArray
        ): WorkoutCheckInOutcome<UploadedCheckInMedia> {
            uploadedIds += clientUploadId
            return if (uploadedIds.size <= uploadResults.size) {
                uploadResults[uploadedIds.size - 1]
            } else {
                uploadResults.lastOrNull()
                    ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
            }
        }

        override suspend fun feed(limit: Int?): WorkoutCheckInOutcome<List<WorkoutCheckIn>> =
            WorkoutCheckInOutcome.Success(emptyList())

        override suspend fun deleteCheckIn(checkInId: String): WorkoutCheckInOutcome<Unit> =
            WorkoutCheckInOutcome.Success(Unit)
    }

    private class FakeSocialGateway : SocialGateway {
        override val isConfigured: Boolean = true
        var status: SocialProfileStatus? = SocialProfileStatus.ACTIVE
        var failure: SocialError? = null

        override suspend fun profile(): SocialOutcome {
            failure?.let { return SocialOutcome.Failure(it) }
            val current = status ?: return SocialOutcome.NotEnabled
            return SocialOutcome.Success(
                SocialProfile(
                    socialId = "social-a",
                    friendCode = "SPK-AAAAAAAA",
                    displayName = "Alice",
                    status = current,
                    privacy = SocialPrivacySettings(
                        discoverability = SocialDiscoverability.FRIEND_CODE_ONLY,
                        friendRequestsEnabled = true,
                        activitySharingEnabled = false
                    ),
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        }

        override suspend fun activate(displayName: String): SocialOutcome =
            throw UnsupportedOperationException()

        override suspend fun updateDisplayName(displayName: String): SocialOutcome =
            throw UnsupportedOperationException()

        override suspend fun updatePrivacy(
            discoverability: SocialDiscoverability?,
            friendRequestsEnabled: Boolean?,
            activitySharingEnabled: Boolean?,
            activityTimeZoneId: String?,
            friendRankingParticipationEnabled: Boolean?
        ): SocialOutcome = throw UnsupportedOperationException()

        override suspend fun disable(): SocialOutcome = throw UnsupportedOperationException()

        override suspend fun enable(): SocialOutcome = throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Executores diretos: o Room roda nos executores **dele**, e um relógio virtual não faz o
        // banco responder mais cedo. Sem isto, `advanceUntilIdle()` volta enquanto a corrotina da
        // ViewModel ainda espera uma consulta em outra thread — e o teste mede o estado errado.
        val inline = java.util.concurrent.Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        gateway = FakeCheckInGateway()
        socialGateway = FakeSocialGateway()
        authGateway = FakeAuthGateway(initialAccount = SparkAccount("uid-a", "Alice"))
        viewModelStore = ViewModelStore()

        val publisher = WorkoutCheckInPublisher(
            workoutDao = database.workoutDao(),
            gateway = gateway,
            authGateway = authGateway,
            syncCycle = { SyncOutcome.Success() },
            clock = { now }
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WorkoutCheckInViewModel(publisher, socialGateway, authGateway) as T
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[WorkoutCheckInViewModel::class.java]
    }

    @After
    fun tearDown() {
        // A ordem importa: cancelar o `viewModelScope` antes de fechar o banco embaixo dele.
        viewModelStore.clear()
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun completedSession(
        finishedAt: Long = now - 30 * 60 * 1000L,
        status: SessionStatus = SessionStatus.COMPLETED
    ): Long = database.workoutDao().insertSession(
        WorkoutSessionEntity(
            templateId = null,
            startedAt = now - 60 * 60 * 1000L,
            finishedAt = finishedAt,
            status = status.name
        )
    )

    // ------------------------------------------------------------------ §143 elegibilidade

    @Test
    fun `treino concluido e recente com Social ativo mostra o CTA`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canShare)
    }

    @Test
    fun `treino nao concluido nao mostra o CTA`() = runTest(testDispatcher) {
        val sessionId = completedSession(status = SessionStatus.IN_PROGRESS)
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canShare)
        assertEquals(CheckInEligibility.NotCompleted, viewModel.uiState.value.eligibility)
    }

    @Test
    fun `treino antigo nao mostra o CTA`() = runTest(testDispatcher) {
        val sessionId = completedSession(finishedAt = now - CHECKIN_LOCAL_WINDOW_MS - 1000L)
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canShare)
    }

    @Test
    fun `sem conta conectada nao mostra o CTA`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        authGateway.signOut()
        advanceUntilIdle()

        viewModel.prepare(sessionId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canShare)
    }

    @Test
    fun `Social desativado nao mostra o CTA`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        socialGateway.status = SocialProfileStatus.DISABLED
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canShare)
        assertFalse(viewModel.uiState.value.isSocialActive)
    }

    @Test
    fun `Social nunca ativado nao mostra o CTA`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        socialGateway.status = null
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canShare)
    }

    @Test
    fun `backend indisponivel nao mostra o CTA`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        socialGateway.failure = SocialError.UNAVAILABLE
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canShare)
    }

    // ------------------------------------------------------------------ §144 confirmação

    @Test
    fun `o primeiro toque no CTA abre o preview e nao publica nada`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        viewModel.requestShare()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConfirming)
        assertEquals(0, gateway.createCalls)
        assertFalse(viewModel.uiState.value.isShared)
    }

    @Test
    fun `cancelar o preview nao publica nada`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        viewModel.requestShare()
        viewModel.cancelShare()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfirming)
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `so a confirmacao publica`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        viewModel.requestShare()
        viewModel.confirmShare()
        advanceUntilIdle()

        assertEquals(1, gateway.createCalls)
        assertTrue(viewModel.uiState.value.isShared)
        assertEquals(CheckInShareFeedback.Published, viewModel.uiState.value.feedback)
    }

    @Test
    fun `toque duplo na confirmacao nao gera duas publicacoes`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        viewModel.requestShare()
        viewModel.confirmShare()
        viewModel.confirmShare()
        advanceUntilIdle()

        assertEquals(1, gateway.createCalls)
    }

    @Test
    fun `a segunda tentativa na mesma tela reusa o clientRequestId`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        gateway.createResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
        viewModel.requestShare()
        viewModel.confirmShare()
        advanceUntilIdle()
        viewModel.dismissFeedback()

        gateway.createResult = WorkoutCheckInOutcome.Success(
            WorkoutCheckIn("checkin-1", SocialCheckInAuthor("s", "Alice"), now, isCurrentUser = true)
        )
        viewModel.requestShare()
        viewModel.confirmShare()
        advanceUntilIdle()

        assertEquals(2, gateway.createCalls)
        assertEquals(
            "a mesma intenção precisa carregar o mesmo id",
            gateway.lastRequestIds[0],
            gateway.lastRequestIds[1]
        )
    }

    // ------------------------------------------------------------------ falhas

    @Test
    fun `falha de rede diz que o treino continua salvo`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()

        gateway.createResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)
        viewModel.requestShare()
        viewModel.confirmShare()
        advanceUntilIdle()

        val feedback = viewModel.uiState.value.feedback
        assertTrue(feedback is CheckInShareFeedback.NotShared)
        assertTrue((feedback as CheckInShareFeedback.NotShared).detail.contains("continua salvo"))
        // E nenhum estado social falso: a tela não afirma que publicou.
        assertFalse(viewModel.uiState.value.isShared)
    }

    // ------------------------------------------------------------------ §150 troca de conta

    @Test
    fun `trocar de conta limpa o estado da publicacao`() = runTest(testDispatcher) {
        val sessionId = completedSession()
        viewModel.prepare(sessionId)
        advanceUntilIdle()
        viewModel.requestShare()
        viewModel.confirmShare()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isShared)

        authGateway.signOut()
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bob"))
        authGateway.signIn(context)
        advanceUntilIdle()

        // Bob não herda "Check-in compartilhado ✓" de Alice.
        assertFalse(viewModel.uiState.value.isShared)
        assertNull(viewModel.uiState.value.feedback)
    }

    @Test
    fun `trocar de sessao zera o estado da sessao anterior`() = runTest(testDispatcher) {
        val first = completedSession()
        viewModel.prepare(first)
        advanceUntilIdle()
        viewModel.requestShare()
        viewModel.confirmShare()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isShared)

        val second = completedSession()
        viewModel.prepare(second)
        advanceUntilIdle()

        // O segundo treino nunca foi publicado, e a tela não pode dizer que foi.
        assertFalse(viewModel.uiState.value.isShared)
        assertTrue(viewModel.uiState.value.canShare)
    }

    // ------------------------------------------------------------------ §23/§105 histórico

    @Test
    fun `o retry do Historico abre o preview quando a sessao ainda e elegivel`() =
        runTest(testDispatcher) {
            val sessionId = completedSession()

            viewModel.startShareFor(sessionId)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isConfirming)
            assertEquals(0, gateway.createCalls)
        }

    @Test
    fun `o retry do Historico explica quando a janela passou`() = runTest(testDispatcher) {
        val sessionId = completedSession(finishedAt = now - CHECKIN_LOCAL_WINDOW_MS - 1000L)

        viewModel.startShareFor(sessionId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConfirming)
        assertEquals(0, gateway.createCalls)
        val feedback = viewModel.uiState.value.feedback
        assertTrue(feedback is CheckInShareFeedback.NotShared)
        assertTrue((feedback as CheckInShareFeedback.NotShared).detail.contains("prazo"))
    }
}
