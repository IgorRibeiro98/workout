package com.example.presentation.friends

import com.example.data.repository.WorkoutShareImportResult
import com.example.data.repository.WorkoutShareImporter
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareError
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareOtherUser
import com.example.domain.social.WorkoutShareOutcome
import com.example.domain.social.WorkoutShareStatus
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

@OptIn(ExperimentalCoroutinesApi::class)
class SharedWorkoutsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
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

    private class FakeWorkoutShareGateway : WorkoutShareGateway {
        override var isConfigured: Boolean = true
        var receivedShares = mutableListOf<WorkoutShareItem>()
        var sentShares = mutableListOf<WorkoutShareItem>()
        var detailResult: WorkoutShareOutcome<WorkoutShareDetail>? = null
        var declineResult: WorkoutShareOutcome<Unit> = WorkoutShareOutcome.Success(Unit)
        var cancelResult: WorkoutShareOutcome<Unit> = WorkoutShareOutcome.Success(Unit)

        var lastDeclinedShareId: String? = null
        var lastCancelledShareId: String? = null

        override suspend fun createShare(
            recipientSocialId: String,
            clientRequestId: String,
            snapshot: SharedWorkoutSnapshot
        ): WorkoutShareOutcome<WorkoutShareDetail> = throw UnsupportedOperationException()

        override suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(receivedShares)

        override suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(sentShares)

        override suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
            detailResult ?: WorkoutShareOutcome.Failure(WorkoutShareError.SHARE_NOT_FOUND)

        override suspend fun acceptShare(shareId: String): WorkoutShareOutcome<SharedWorkoutSnapshot> =
            throw UnsupportedOperationException()

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
        var importResult: WorkoutShareImportResult = WorkoutShareImportResult.Success(99L)
        var lastImportedShareId: String? = null
        var lastImportedSnapshot: SharedWorkoutSnapshot? = null

        override suspend fun importShare(
            shareId: String,
            snapshot: SharedWorkoutSnapshot,
            targetProgramId: Long?
        ): WorkoutShareImportResult {
            lastImportedShareId = shareId
            lastImportedSnapshot = snapshot
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
        fakeGateway.receivedShares.add(
            WorkoutShareItem(
                shareId = "share-1",
                templateName = "Treino PPL",
                exerciseCount = 5,
                status = WorkoutShareStatus.PENDING,
                createdAt = 1000L,
                expiresAt = 2000L,
                otherUser = WorkoutShareOtherUser(
                    socialId = "friend-1",
                    displayName = "Carlos"
                )
            )
        )
        fakeGateway.sentShares.add(
            WorkoutShareItem(
                shareId = "share-2",
                templateName = "Treino Braço",
                exerciseCount = 4,
                status = WorkoutShareStatus.PENDING,
                createdAt = 1000L,
                expiresAt = 2000L,
                otherUser = WorkoutShareOtherUser(
                    socialId = "friend-2",
                    displayName = "Mariana"
                )
            )
        )

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.receivedItems.size)
        assertEquals("Treino PPL", state.receivedItems[0].templateName)
        assertEquals(1, state.sentItems.size)
        assertEquals("Treino Braço", state.sentItems[0].templateName)
    }

    @Test
    fun `openDetail loads preview and closeDetail clears it`() = runTest(testDispatcher) {
        val detail = WorkoutShareDetail(
            shareId = "share-1",
            status = WorkoutShareStatus.PENDING,
            createdAt = 1000L,
            expiresAt = 2000L,
            sender = WorkoutShareOtherUser(socialId = "friend-1", displayName = "Carlos"),
            recipient = WorkoutShareOtherUser(socialId = "user-1", displayName = "Atleta"),
            snapshot = sampleSnapshot
        )
        fakeGateway.detailResult = WorkoutShareOutcome.Success(detail)

        viewModel.openDetail("share-1")
        advanceUntilIdle()

        assertEquals(detail, viewModel.uiState.value.previewDetail)

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.previewDetail)
    }

    @Test
    fun `importWorkout invokes importer and shows success notice`() = runTest(testDispatcher) {
        fakeImporter.importResult = WorkoutShareImportResult.Success(99L)

        viewModel.importWorkout("share-1", sampleSnapshot)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("share-1", fakeImporter.lastImportedShareId)
        assertNull(state.importingShareId)
        assertNull(state.previewDetail)
        assertEquals("Treino adicionado com sucesso aos seus treinos!", state.notice)

        viewModel.dismissNotice()
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `declineShare calls gateway and sets notice`() = runTest(testDispatcher) {
        viewModel.declineShare("share-1")
        advanceUntilIdle()

        assertEquals("share-1", fakeGateway.lastDeclinedShareId)
        assertEquals("Oferta de treino recusada.", viewModel.uiState.value.notice)
    }

    @Test
    fun `cancelShare calls gateway and sets notice`() = runTest(testDispatcher) {
        viewModel.cancelShare("share-2")
        advanceUntilIdle()

        assertEquals("share-2", fakeGateway.lastCancelledShareId)
        assertEquals("Compartilhamento cancelado.", viewModel.uiState.value.notice)
    }
}
