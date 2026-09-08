package com.example.presentation.friends

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.BlockError
import com.example.domain.social.BlockGateway
import com.example.domain.social.BlockOutcome
import com.example.domain.social.BlockedUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BlockedUsersViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `open carrega usuarios bloqueados`() = runTest {
        val user = BlockedUser("s-1", "Bloqueado 1", 1000L)
        val fakeBlock = FakeBlockGateway(initialBlocked = listOf(user))
        val fakeAuth = FakeAuthGateway(initialAccount = SparkAccount("uid-1", "Me"))
        val viewModel = BlockedUsersViewModel(fakeBlock, fakeAuth)

        viewModel.open()

        val state = viewModel.uiState.value
        assertTrue(state.phase is BlockedUsersPhase.Ready)
        assertEquals(listOf(user), (state.phase as BlockedUsersPhase.Ready).users)
    }

    @Test
    fun `unblockUser remove usuario da lista local e exibe aviso`() = runTest {
        val user1 = BlockedUser("s-1", "Bloqueado 1", 1000L)
        val user2 = BlockedUser("s-2", "Bloqueado 2", 2000L)
        val fakeBlock = FakeBlockGateway(initialBlocked = listOf(user1, user2))
        val fakeAuth = FakeAuthGateway(initialAccount = SparkAccount("uid-1", "Me"))
        val viewModel = BlockedUsersViewModel(fakeBlock, fakeAuth)

        viewModel.open()
        viewModel.unblockUser("s-1")

        val state = viewModel.uiState.value
        assertTrue(state.phase is BlockedUsersPhase.Ready)
        val currentUsers = (state.phase as BlockedUsersPhase.Ready).users
        assertEquals(1, currentUsers.size)
        assertEquals("s-2", currentUsers[0].socialId)
        assertEquals("Usuário desbloqueado.", state.notice)
    }

    @Test
    fun `troca de conta reseta estado para Idle`() = runTest {
        val user = BlockedUser("s-1", "Bloqueado 1", 1000L)
        val fakeBlock = FakeBlockGateway(initialBlocked = listOf(user))
        val fakeAuth = FakeAuthGateway(initialAccount = SparkAccount("uid-1", "Me"))
        val viewModel = BlockedUsersViewModel(fakeBlock, fakeAuth)

        viewModel.open()
        assertTrue(viewModel.uiState.value.phase is BlockedUsersPhase.Ready)

        fakeAuth.signOut()
        assertTrue(viewModel.uiState.value.phase is BlockedUsersPhase.Idle)
    }

    @Test
    fun `falha de rede mapeia para Offline`() = runTest {
        val fakeBlock = FakeBlockGateway(error = BlockError.NETWORK)
        val fakeAuth = FakeAuthGateway(initialAccount = SparkAccount("uid-1", "Me"))
        val viewModel = BlockedUsersViewModel(fakeBlock, fakeAuth)

        viewModel.open()
        assertTrue(viewModel.uiState.value.phase is BlockedUsersPhase.Offline)
    }

    private class FakeBlockGateway(
        private val initialBlocked: List<BlockedUser> = emptyList(),
        private val error: BlockError? = null
    ) : BlockGateway {
        override val isConfigured: Boolean = true
        val unblockedIds = mutableListOf<String>()

        override suspend fun blockUser(socialId: String): BlockOutcome<Unit> {
            if (error != null) return BlockOutcome.Failure(error)
            return BlockOutcome.Success(Unit)
        }

        override suspend fun unblockUser(socialId: String): BlockOutcome<Unit> {
            if (error != null) return BlockOutcome.Failure(error)
            unblockedIds.add(socialId)
            return BlockOutcome.Success(Unit)
        }

        override suspend fun listBlockedUsers(): BlockOutcome<List<BlockedUser>> {
            if (error != null) return BlockOutcome.Failure(error)
            return BlockOutcome.Success(initialBlocked.filterNot { it.socialId in unblockedIds })
        }
    }
}
