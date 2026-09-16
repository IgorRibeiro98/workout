package com.example.presentation.friends

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.repository.WorkoutShareImportResult
import com.example.data.repository.WorkoutShareImporter
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedProgramSnapshot
import com.example.domain.social.SharedProgramTemplateSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareContent
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareError
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareKind
import com.example.domain.social.WorkoutShareOtherUser
import com.example.domain.social.WorkoutShareOutcome
import com.example.domain.social.WorkoutShareStatus
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric só pela troca de conta: o dublê de autenticação entra pelo caminho real
 * (`signIn(host)`), que pede um `Context` — e um teste de JVM puro não tem um.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SharedWorkoutsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var fakeGateway: FakeWorkoutShareGateway
    private lateinit var fakeImporter: FakeWorkoutShareImporter
    private lateinit var authGateway: FakeAuthGateway
    private lateinit var viewModel: SharedWorkoutsViewModel

    private val sampleSnapshot = SharedWorkoutSnapshot(
        snapshotVersion = 1,
        name = "Treino Teste",
        shortIdentifier = "T",
        exercises = listOf(
            SharedExerciseSnapshot(
                canonicalExerciseId = "cat-1",
                sortOrder = 0,
                targetSets = 3,
                minReps = 10,
                maxReps = 12,
                restDurationSeconds = 60
            )
        )
    )

    private val sampleProgram = SharedProgramSnapshot(
        name = "PPL",
        templates = listOf(
            SharedProgramTemplateSnapshot(
                name = "Push",
                shortIdentifier = "A",
                orderInProgram = 0,
                exercises = sampleSnapshot.exercises
            )
        )
    )

    private fun item(shareId: String, name: String, kind: WorkoutShareKind = WorkoutShareKind.WORKOUT_TEMPLATE) =
        WorkoutShareItem(
            shareId = shareId,
            kind = kind,
            templateName = name,
            templateCount = if (kind == WorkoutShareKind.WORKOUT_PROGRAM) 3 else 1,
            exerciseCount = 5,
            status = WorkoutShareStatus.PENDING,
            createdAt = 1000L,
            expiresAt = 2000L,
            otherUser = WorkoutShareOtherUser(socialId = "friend-1", displayName = "Carlos")
        )

    private class FakeWorkoutShareGateway : WorkoutShareGateway {
        override var isConfigured: Boolean = true
        var receivedShares = mutableListOf<WorkoutShareItem>()
        var sentShares = mutableListOf<WorkoutShareItem>()
        var detailResult: WorkoutShareOutcome<WorkoutShareDetail>? = null
        var declineResult: WorkoutShareOutcome<Unit> = WorkoutShareOutcome.Success(Unit)
        var cancelResult: WorkoutShareOutcome<Unit> = WorkoutShareOutcome.Success(Unit)

        /** Quando presente, `listReceived` espera por ela — para simular uma resposta atrasada. */
        var receivedGate: CompletableDeferred<Unit>? = null

        var lastDeclinedShareId: String? = null
        var lastCancelledShareId: String? = null
        var listReceivedCalls = 0

        override suspend fun createShare(
            recipientSocialId: String,
            clientRequestId: String,
            content: WorkoutShareContent
        ): WorkoutShareOutcome<WorkoutShareDetail> = throw UnsupportedOperationException()

        override suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>> {
            listReceivedCalls++
            // A resposta é o que a lista tinha **quando a chamada saiu**: é assim que a chamada
            // atrasada da conta anterior carrega os dados dela, e não os da conta nova.
            val response = receivedShares.toList()
            receivedGate?.await()
            return WorkoutShareOutcome.Success(response)
        }

        override suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(sentShares.toList())

        override suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
            detailResult ?: WorkoutShareOutcome.Failure(WorkoutShareError.SHARE_NOT_FOUND)

        override suspend fun acceptShare(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
            throw UnsupportedOperationException("a tela não aceita por conta própria: é o importador")

        override suspend fun completeImport(shareId: String): WorkoutShareOutcome<Unit> =
            WorkoutShareOutcome.Success(Unit)

        override suspend fun declineShare(shareId: String): WorkoutShareOutcome<Unit> {
            lastDeclinedShareId = shareId
            return declineResult
        }

        override suspend fun cancelShare(shareId: String): WorkoutShareOutcome<Unit> {
            lastCancelledShareId = shareId
            return cancelResult
        }
    }

    private class FakeWorkoutShareImporter : WorkoutShareImporter() {
        var importResult: WorkoutShareImportResult =
            WorkoutShareImportResult.Success(WorkoutShareKind.WORKOUT_TEMPLATE, 99L)
        val importedShareIds = mutableListOf<String>()

        /** Quando presente, a importação espera por ela — para simular o toque duplo. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun acceptAndImport(shareId: String): WorkoutShareImportResult {
            importedShareIds.add(shareId)
            gate?.await()
            return importResult
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGateway = FakeWorkoutShareGateway()
        fakeImporter = FakeWorkoutShareImporter()
        authGateway = FakeAuthGateway(initialAccount = SparkAccount("user-1", "Atleta"))
        viewModel = SharedWorkoutsViewModel(
            shareGateway = fakeGateway,
            shareImporter = fakeImporter,
            authGateway = authGateway
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has tab RECEIVED and empty lists`() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(SharedWorkoutsTab.RECEIVED, state.selectedTab)
        assertFalse(state.isLoading)
        assertTrue(state.receivedItems.isEmpty())
        assertTrue(state.sentItems.isEmpty())
    }

    @Test
    fun `selectTab updates selectedTab in uiState`() = runTest(testDispatcher) {
        viewModel.selectTab(SharedWorkoutsTab.SENT)
        assertEquals(SharedWorkoutsTab.SENT, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun `refresh populates received and sent shares from gateway`() = runTest(testDispatcher) {
        fakeGateway.receivedShares.add(item("share-1", "Treino PPL"))
        fakeGateway.receivedShares.add(item("share-p", "Programa PPL", WorkoutShareKind.WORKOUT_PROGRAM))
        fakeGateway.sentShares.add(item("share-2", "Treino Braço"))

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.receivedItems.size)
        assertEquals("Treino PPL", state.receivedItems[0].templateName)
        assertEquals(WorkoutShareKind.WORKOUT_PROGRAM, state.receivedItems[1].kind)
        assertEquals(1, state.sentItems.size)
        assertEquals("Treino Braço", state.sentItems[0].templateName)
    }

    @Test
    fun `openDetail loads preview and closeDetail clears it`() = runTest(testDispatcher) {
        val detail = WorkoutShareDetail(
            shareId = "share-1",
            kind = WorkoutShareKind.WORKOUT_TEMPLATE,
            status = WorkoutShareStatus.PENDING,
            createdAt = 1000L,
            expiresAt = 2000L,
            sender = WorkoutShareOtherUser(socialId = "friend-1", displayName = "Carlos"),
            recipient = WorkoutShareOtherUser(socialId = "user-1", displayName = "Atleta"),
            content = WorkoutShareContent.Workout(sampleSnapshot)
        )
        fakeGateway.detailResult = WorkoutShareOutcome.Success(detail)

        viewModel.openDetail("share-1")
        advanceUntilIdle()

        assertEquals(detail, viewModel.uiState.value.previewDetail)

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.previewDetail)
    }

    @Test
    fun `importShare invokes importer and shows success notice for a template`() = runTest(testDispatcher) {
        fakeImporter.importResult = WorkoutShareImportResult.Success(WorkoutShareKind.WORKOUT_TEMPLATE, 99L)

        viewModel.importShare("share-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("share-1"), fakeImporter.importedShareIds)
        assertNull(state.importingShareId)
        assertNull(state.previewDetail)
        assertEquals("Treino adicionado com sucesso aos seus treinos!", state.notice)

        viewModel.dismissNotice()
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `importShare of a program says it did not become current`() = runTest(testDispatcher) {
        fakeImporter.importResult = WorkoutShareImportResult.Success(WorkoutShareKind.WORKOUT_PROGRAM, 7L)

        viewModel.importShare("share-p")
        advanceUntilIdle()

        val notice = viewModel.uiState.value.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("Programa adicionado"))
        assertTrue("receber não ativa o programa, e a tela diz isso", notice.contains("Ative-o"))
    }

    @Test
    fun `importShare reports an already imported program without importing again`() = runTest(testDispatcher) {
        fakeImporter.importResult = WorkoutShareImportResult.AlreadyImported(WorkoutShareKind.WORKOUT_PROGRAM, 7L)

        viewModel.importShare("share-p")
        advanceUntilIdle()

        assertEquals("Este programa já foi adicionado aos seus programas anteriormente.", viewModel.uiState.value.notice)
        assertNull(viewModel.uiState.value.previewDetail)
    }

    @Test
    fun `importShare rejected by the server explains why and refreshes the list`() = runTest(testDispatcher) {
        advanceUntilIdle()
        val callsBefore = fakeGateway.listReceivedCalls
        fakeImporter.importResult = WorkoutShareImportResult.Rejected(WorkoutShareError.SHARE_NOT_FOUND)

        viewModel.importShare("share-blocked")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Esta oferta não está mais disponível.", state.notice)
        assertNull(state.importingShareId)
        assertNull(state.previewDetail)
        assertEquals("a lista é relida: a oferta mudou de estado no servidor", callsBefore + 1, fakeGateway.listReceivedCalls)
    }

    @Test
    fun `importShare offline says nothing was added and keeps the preview`() = runTest(testDispatcher) {
        val detail = WorkoutShareDetail(
            shareId = "share-p",
            kind = WorkoutShareKind.WORKOUT_PROGRAM,
            status = WorkoutShareStatus.PENDING,
            createdAt = 1000L,
            expiresAt = 2000L,
            sender = WorkoutShareOtherUser(socialId = "friend-1", displayName = "Carlos"),
            recipient = WorkoutShareOtherUser(socialId = "user-1", displayName = "Atleta"),
            content = WorkoutShareContent.Program(sampleProgram)
        )
        fakeGateway.detailResult = WorkoutShareOutcome.Success(detail)
        viewModel.openDetail("share-p")
        advanceUntilIdle()
        fakeImporter.importResult = WorkoutShareImportResult.Rejected(WorkoutShareError.NETWORK)

        viewModel.importShare("share-p")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.notice!!.contains("Sem conexão"))
        assertEquals("a prévia continua aberta para tentar de novo", detail, state.previewDetail)
    }

    @Test
    fun `double tap on import runs the importer once`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        fakeImporter.gate = gate
        fakeImporter.importResult = WorkoutShareImportResult.Success(WorkoutShareKind.WORKOUT_PROGRAM, 7L)

        viewModel.importShare("share-p")
        viewModel.importShare("share-p")
        advanceUntilIdle()
        assertEquals("share-p", viewModel.uiState.value.importingShareId)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("share-p"), fakeImporter.importedShareIds)
        assertNull(viewModel.uiState.value.importingShareId)
    }

    @Test
    fun `declineShare calls gateway and sets notice`() = runTest(testDispatcher) {
        viewModel.declineShare("share-1")
        advanceUntilIdle()

        assertEquals("share-1", fakeGateway.lastDeclinedShareId)
        assertEquals("Oferta recusada.", viewModel.uiState.value.notice)
    }

    @Test
    fun `cancelShare calls gateway and sets notice`() = runTest(testDispatcher) {
        viewModel.cancelShare("share-2")
        advanceUntilIdle()

        assertEquals("share-2", fakeGateway.lastCancelledShareId)
        assertEquals("Compartilhamento cancelado.", viewModel.uiState.value.notice)
    }

    // ------------------------------------------------------------------ escopo de conta

    @Test
    fun `switching account clears the offers before the new account loads`() = runTest(testDispatcher) {
        fakeGateway.receivedShares.add(item("share-a", "Treino de A"))
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.receivedItems.size)

        fakeGateway.receivedShares.clear()
        signInAs("user-2", "Outra")
        advanceUntilIdle()

        assertTrue("as ofertas da conta anterior não podem ficar na tela", viewModel.uiState.value.receivedItems.isEmpty())
    }

    @Test
    fun `a late response from the previous account is dropped`() = runTest(testDispatcher) {
        advanceUntilIdle()
        // A conta 1 pede a lista, e a resposta demora.
        val gate = CompletableDeferred<Unit>()
        fakeGateway.receivedGate = gate
        fakeGateway.receivedShares.add(item("share-a", "Treino de A"))
        viewModel.refresh()
        advanceUntilIdle()

        // Troca de conta antes de a resposta chegar. A conta 2 não tem ofertas, e a leitura dela
        // também espera pelo mesmo portão.
        fakeGateway.receivedShares.clear()
        signInAs("user-2", "Outra")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.receivedItems.isEmpty())

        // As duas respostas chegam. A da conta 1 carrega o share de A — e precisa ser descartada.
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            "a resposta atrasada da conta anterior não pode preencher a tela da conta nova",
            viewModel.uiState.value.receivedItems.none { it.shareId == "share-a" }
        )
    }

    @Test
    fun `a late import result from the previous account is dropped`() = runTest(testDispatcher) {
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fakeImporter.gate = gate
        fakeImporter.importResult = WorkoutShareImportResult.Success(WorkoutShareKind.WORKOUT_PROGRAM, 7L)

        viewModel.importShare("share-p")
        advanceUntilIdle()

        signInAs("user-2", "Outra")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull("o aviso de sucesso pertence à conta anterior", viewModel.uiState.value.notice)
    }

    @Test
    fun `signed out shows nothing and does not import`() = runTest(testDispatcher) {
        authGateway.signOut()
        advanceUntilIdle()

        viewModel.importShare("share-p")
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(fakeImporter.importedShareIds.isEmpty())
        assertTrue(viewModel.uiState.value.receivedItems.isEmpty())
    }

    /** Troca a sessão pelo caminho real do dublê: sair e entrar de novo, com outra conta. */
    private suspend fun signInAs(uid: String, name: String) {
        authGateway.signOut()
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount(uid, name))
        authGateway.signIn(context)
    }
}
