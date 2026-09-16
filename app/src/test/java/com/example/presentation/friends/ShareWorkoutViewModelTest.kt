package com.example.presentation.friends

import com.example.data.social.FakeFriendGateway
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O diálogo de compartilhar (T17.7 / T19.3): carregar amigos, enviar um treino ou um programa,
 * dizer o que deu errado, e não enviar duas vezes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareWorkoutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var friendGateway: FakeFriendGateway
    private lateinit var shareGateway: RecordingShareGateway
    private lateinit var viewModel: ShareWorkoutViewModel

    private val workout = WorkoutShareContent.Workout(
        SharedWorkoutSnapshot(
            name = "Push",
            shortIdentifier = "A",
            exercises = listOf(SharedExerciseSnapshot("cat-bench", 0, 4, 8, 10, 90))
        )
    )

    private val program = WorkoutShareContent.Program(
        SharedProgramSnapshot(
            name = "PPL",
            templates = listOf(
                SharedProgramTemplateSnapshot(
                    name = "Push",
                    shortIdentifier = "A",
                    orderInProgram = 0,
                    exercises = listOf(SharedExerciseSnapshot("cat-bench", 0, 4, 8, 10, 90))
                ),
                SharedProgramTemplateSnapshot(
                    name = "Pull",
                    shortIdentifier = "B",
                    orderInProgram = 1,
                    exercises = listOf(SharedExerciseSnapshot("cat-row", 0, 4, 8, 10, 90))
                )
            )
        )
    )

    private class RecordingShareGateway : WorkoutShareGateway {
        override val isConfigured: Boolean = true
        val sent = mutableListOf<Triple<String, String, WorkoutShareContent>>()
        var result: WorkoutShareOutcome<WorkoutShareDetail>? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun createShare(
            recipientSocialId: String,
            clientRequestId: String,
            content: WorkoutShareContent
        ): WorkoutShareOutcome<WorkoutShareDetail> {
            sent += Triple(recipientSocialId, clientRequestId, content)
            gate?.await()
            return result ?: WorkoutShareOutcome.Success(
                WorkoutShareDetail(
                    shareId = "share-${sent.size}",
                    kind = content.kind,
                    status = WorkoutShareStatus.PENDING,
                    createdAt = 1L,
                    expiresAt = 2L,
                    sender = WorkoutShareOtherUser("me", "Eu"),
                    recipient = WorkoutShareOtherUser(recipientSocialId, "Amigo"),
                    content = content
                )
            )
        }

        override suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(emptyList())

        override suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(emptyList())

        override suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
            throw UnsupportedOperationException()

        override suspend fun acceptShare(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
            throw UnsupportedOperationException()

        override suspend fun completeImport(shareId: String): WorkoutShareOutcome<Unit> =
            throw UnsupportedOperationException()

        override suspend fun declineShare(shareId: String): WorkoutShareOutcome<Unit> =
            throw UnsupportedOperationException()

        override suspend fun cancelShare(shareId: String): WorkoutShareOutcome<Unit> =
            throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        friendGateway = FakeFriendGateway().apply {
            currentUid = "uid-me"
            register(FakeFriendGateway.Account("uid-me", "social-me", "SPK-AAAAAAAA", "Eu"))
            register(FakeFriendGateway.Account("uid-ana", "social-ana", "SPK-BBBBBBBB", "Ana"))
            seedFriendship("uid-me", "uid-ana")
        }
        shareGateway = RecordingShareGateway()
        viewModel = ShareWorkoutViewModel(friendGateway, shareGateway)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadFriends lists the friends and clears loading`() = runTest(testDispatcher) {
        viewModel.loadFriends()
        assertTrue(viewModel.uiState.value.isLoadingFriends)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoadingFriends)
        assertEquals(listOf("Ana"), state.friends.map { it.displayName })
        assertNull(state.errorMessage)
    }

    @Test
    fun `share without a selected friend does nothing`() = runTest(testDispatcher) {
        viewModel.share(program)
        advanceUntilIdle()
        assertTrue(shareGateway.sent.isEmpty())
        assertFalse(viewModel.uiState.value.isSent)
    }

    @Test
    fun `share sends the program content to the selected friend and confirms`() = runTest(testDispatcher) {
        viewModel.loadFriends()
        advanceUntilIdle()
        viewModel.selectFriend("social-ana")

        viewModel.share(program)
        assertTrue(viewModel.uiState.value.isSending)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertTrue(state.isSent)
        assertNull(state.errorMessage)
        assertEquals(1, shareGateway.sent.size)
        val (recipient, clientRequestId, content) = shareGateway.sent.single()
        assertEquals("social-ana", recipient)
        assertTrue(clientRequestId.isNotBlank())
        assertEquals(program, content)
        assertEquals(WorkoutShareKind.WORKOUT_PROGRAM, content.kind)
    }

    @Test
    fun `share sends a template exactly as before`() = runTest(testDispatcher) {
        viewModel.selectFriend("social-ana")
        viewModel.share(workout)
        advanceUntilIdle()

        assertEquals(workout, shareGateway.sent.single().third)
        assertTrue(viewModel.uiState.value.isSent)
    }

    @Test
    fun `double tap while sending sends once`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        shareGateway.gate = gate
        viewModel.selectFriend("social-ana")

        viewModel.share(program)
        viewModel.share(program)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, shareGateway.sent.size)
        assertTrue(viewModel.uiState.value.isSent)
    }

    @Test
    fun `a retry after failure is a new attempt with a new clientRequestId`() = runTest(testDispatcher) {
        viewModel.selectFriend("social-ana")
        shareGateway.result = WorkoutShareOutcome.Failure(WorkoutShareError.NETWORK)

        viewModel.share(program)
        advanceUntilIdle()
        val failed = viewModel.uiState.value
        assertFalse(failed.isSent)
        assertNotNull(failed.errorMessage)
        assertTrue(failed.errorMessage!!.contains("Sem conexão"))
        assertTrue("a mensagem fala de programa, não de treino", failed.errorMessage!!.contains("programa"))

        shareGateway.result = null
        viewModel.share(program)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSent)
        assertEquals(2, shareGateway.sent.size)
        assertNotEquals(shareGateway.sent[0].second, shareGateway.sent[1].second)
    }

    @Test
    fun `server refusals are explained by reason`() = runTest(testDispatcher) {
        viewModel.selectFriend("social-ana")
        for ((error, fragment) in listOf(
            WorkoutShareError.FRIENDSHIP_REQUIRED to "amigos",
            WorkoutShareError.BLOCKED_USER to "Não é possível",
            WorkoutShareError.RATE_LIMITED to "limite",
            WorkoutShareError.SOCIAL_NOT_ENABLED to "Social",
            WorkoutShareError.AUTH_REQUIRED to "Conta Spark"
        )) {
            shareGateway.result = WorkoutShareOutcome.Failure(error)
            viewModel.share(program)
            advanceUntilIdle()
            val message = viewModel.uiState.value.errorMessage
            assertTrue("$error deveria mencionar '$fragment': $message", message?.contains(fragment) == true)
        }
    }

    @Test
    fun `loadFriends failure is not the same as having no friends`() = runTest(testDispatcher) {
        friendGateway.currentUid = null
        viewModel.loadFriends()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.friends.isEmpty())
        assertNotNull(state.errorMessage)
    }

    @Test
    fun `reset returns to the initial state`() = runTest(testDispatcher) {
        viewModel.selectFriend("social-ana")
        viewModel.share(program)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSent)

        viewModel.reset()

        assertEquals(ShareWorkoutUiState(), viewModel.uiState.value)
    }
}
