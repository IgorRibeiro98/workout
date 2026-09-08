package com.example.presentation.friends

import com.example.data.social.PushDeviceRegistrationDto
import com.example.domain.social.SocialNotificationGateway
import com.example.domain.social.SocialNotificationPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPreferencesViewModelTest {

    private lateinit var fakeGateway: FakeSocialNotificationGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        fakeGateway = FakeSocialNotificationGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init carrega preferencias com sucesso no estado Loaded`() {
        val defaultPrefs = SocialNotificationPreferences(
            pushEnabled = true,
            friendRequestReceived = true,
            friendRequestAccepted = true,
            challengeInvitationReceived = true,
            challengeStartingSoon = true,
            challengeEnded = true
        )
        fakeGateway.preferences = defaultPrefs

        val viewModel = NotificationPreferencesViewModel(fakeGateway)

        val state = viewModel.uiState.value
        assertTrue(state is NotificationPreferencesUiState.Loaded)
        val loaded = state as NotificationPreferencesUiState.Loaded
        assertEquals(defaultPrefs, loaded.preferences)
        assertFalse(loaded.isUpdating)
        assertNull(loaded.errorMessage)
    }

    @Test
    fun `falha ao carregar coloca estado Error`() {
        fakeGateway.getPreferencesError = IllegalStateException("Falha de rede")

        val viewModel = NotificationPreferencesViewModel(fakeGateway)

        val state = viewModel.uiState.value
        assertTrue(state is NotificationPreferencesUiState.Error)
        val error = state as NotificationPreferencesUiState.Error
        assertEquals("Falha de rede", error.message)
    }

    @Test
    fun `togglePushEnabled atualiza preferencias com sucesso`() {
        val initialPrefs = SocialNotificationPreferences(
            pushEnabled = true,
            friendRequestReceived = true,
            friendRequestAccepted = true,
            challengeInvitationReceived = true,
            challengeStartingSoon = true,
            challengeEnded = true
        )
        fakeGateway.preferences = initialPrefs

        val viewModel = NotificationPreferencesViewModel(fakeGateway)
        viewModel.togglePushEnabled(false)

        val state = viewModel.uiState.value
        assertTrue(state is NotificationPreferencesUiState.Loaded)
        val loaded = state as NotificationPreferencesUiState.Loaded
        assertFalse(loaded.preferences.pushEnabled)
        assertFalse(loaded.isUpdating)
        assertNull(loaded.errorMessage)
    }

    @Test
    fun `toggleFriendRequestReceived atualiza preferencia especifica`() {
        val initialPrefs = SocialNotificationPreferences(
            pushEnabled = true,
            friendRequestReceived = true,
            friendRequestAccepted = true,
            challengeInvitationReceived = true,
            challengeStartingSoon = true,
            challengeEnded = true
        )
        fakeGateway.preferences = initialPrefs

        val viewModel = NotificationPreferencesViewModel(fakeGateway)
        viewModel.toggleFriendRequestReceived(false)

        val state = viewModel.uiState.value
        assertTrue(state is NotificationPreferencesUiState.Loaded)
        val loaded = state as NotificationPreferencesUiState.Loaded
        assertFalse(loaded.preferences.friendRequestReceived)
        assertTrue(loaded.preferences.friendRequestAccepted)
    }

    @Test
    fun `toggleFriendRequestAccepted atualiza com sucesso`() {
        fakeGateway.preferences = SocialNotificationPreferences(pushEnabled = true)
        val viewModel = NotificationPreferencesViewModel(fakeGateway)
        viewModel.toggleFriendRequestAccepted(false)

        val state = viewModel.uiState.value as NotificationPreferencesUiState.Loaded
        assertFalse(state.preferences.friendRequestAccepted)
    }

    @Test
    fun `toggleChallengeInvitationReceived atualiza com sucesso`() {
        fakeGateway.preferences = SocialNotificationPreferences(pushEnabled = true)
        val viewModel = NotificationPreferencesViewModel(fakeGateway)
        viewModel.toggleChallengeInvitationReceived(false)

        val state = viewModel.uiState.value as NotificationPreferencesUiState.Loaded
        assertFalse(state.preferences.challengeInvitationReceived)
    }

    @Test
    fun `toggleChallengeStartingSoon atualiza com sucesso`() {
        fakeGateway.preferences = SocialNotificationPreferences(pushEnabled = true)
        val viewModel = NotificationPreferencesViewModel(fakeGateway)
        viewModel.toggleChallengeStartingSoon(false)

        val state = viewModel.uiState.value as NotificationPreferencesUiState.Loaded
        assertFalse(state.preferences.challengeStartingSoon)
    }

    @Test
    fun `toggleChallengeEnded atualiza com sucesso`() {
        fakeGateway.preferences = SocialNotificationPreferences(pushEnabled = true)
        val viewModel = NotificationPreferencesViewModel(fakeGateway)
        viewModel.toggleChallengeEnded(false)

        val state = viewModel.uiState.value as NotificationPreferencesUiState.Loaded
        assertFalse(state.preferences.challengeEnded)
    }

    @Test
    fun `falha na atualizacao preserva estado atual com mensagem de erro`() {
        val initialPrefs = SocialNotificationPreferences(pushEnabled = true)
        fakeGateway.preferences = initialPrefs

        val viewModel = NotificationPreferencesViewModel(fakeGateway)
        fakeGateway.updatePreferencesError = IllegalStateException("Erro ao salvar")

        viewModel.togglePushEnabled(false)

        val state = viewModel.uiState.value
        assertTrue(state is NotificationPreferencesUiState.Loaded)
        val loaded = state as NotificationPreferencesUiState.Loaded
        assertTrue(loaded.preferences.pushEnabled) // Mantém valor anterior
        assertFalse(loaded.isUpdating)
        assertEquals("Erro ao salvar", loaded.errorMessage)
    }

    private class FakeSocialNotificationGateway : SocialNotificationGateway {
        var preferences = SocialNotificationPreferences(pushEnabled = true)
        var getPreferencesError: Throwable? = null
        var updatePreferencesError: Throwable? = null

        override val isConfigured: Boolean = true

        override suspend fun registerDevice(deviceId: String, fcmToken: String): Result<PushDeviceRegistrationDto> {
            return Result.success(PushDeviceRegistrationDto(deviceId, "ANDROID", true, 0L, 0L))
        }

        override suspend fun unregisterDevice(deviceId: String): Result<Unit> {
            return Result.success(Unit)
        }

        override suspend fun getPreferences(): Result<SocialNotificationPreferences> {
            getPreferencesError?.let { return Result.failure(it) }
            return Result.success(preferences)
        }

        override suspend fun updatePreferences(
            pushEnabled: Boolean?,
            friendRequestReceived: Boolean?,
            friendRequestAccepted: Boolean?,
            challengeInvitationReceived: Boolean?,
            challengeStartingSoon: Boolean?,
            challengeEnded: Boolean?
        ): Result<SocialNotificationPreferences> {
            updatePreferencesError?.let { return Result.failure(it) }
            preferences = preferences.copy(
                pushEnabled = pushEnabled ?: preferences.pushEnabled,
                friendRequestReceived = friendRequestReceived ?: preferences.friendRequestReceived,
                friendRequestAccepted = friendRequestAccepted ?: preferences.friendRequestAccepted,
                challengeInvitationReceived = challengeInvitationReceived ?: preferences.challengeInvitationReceived,
                challengeStartingSoon = challengeStartingSoon ?: preferences.challengeStartingSoon,
                challengeEnded = challengeEnded ?: preferences.challengeEnded
            )
            return Result.success(preferences)
        }
    }
}
