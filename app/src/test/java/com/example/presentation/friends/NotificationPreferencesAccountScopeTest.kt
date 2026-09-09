package com.example.presentation.friends

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.social.PushDeviceRegistrationDto
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SocialNotificationGateway
import com.example.domain.social.SocialNotificationPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Escopo de conta das preferências de notificação (T17.10 §103/§104).
 *
 * ## Por que esta classe existe separada
 *
 * `NotificationPreferencesViewModelTest` cobre o comportamento da tela — carregar, alternar,
 * falhar — e roda como teste JVM puro. A **troca de conta** precisa de um `Context` para
 * `FakeAuthGateway.signIn`, e de um dispatcher controlado para colocar uma resposta em voo
 * atravessando o login da conta seguinte. Misturar as duas necessidades no mesmo arquivo tornaria
 * onze testes rápidos dependentes de Robolectric sem precisarem.
 *
 * ## O defeito que estes testes travam
 *
 * Esta era a única ViewModel social sem disciplina de conta. O estado carregado como A
 * permanecia na tela depois de B entrar, e uma resposta iniciada como A e concluída depois do
 * login de B era escrita no estado de B — de onde o toque seguinte a reenviaria como se fosse
 * preferência de B.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class NotificationPreferencesAccountScopeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var gateway: GatedNotificationGateway
    private lateinit var auth: FakeAuthGateway

    /**
     * Guarda a ViewModel para que o `viewModelScope` seja **cancelado** no fim do teste.
     *
     * Sem isso a corrotina de `init` que observa a sessão sobrevive ao teste, e o
     * `kotlinx-coroutines-test` reporta o vazamento na **próxima** classe da suíte.
     */
    private lateinit var viewModelStore: ViewModelStore

    private val accountA = SparkAccount("uid-a", "Alice", "a@example.com", null)
    private val accountB = SparkAccount("uid-b", "Bruno", "b@example.com", null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        gateway = GatedNotificationGateway()
        auth = FakeAuthGateway(initialAccount = accountA)
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): NotificationPreferencesViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotificationPreferencesViewModel(gateway, auth) as T
        }
        return ViewModelProvider(viewModelStore, factory)[NotificationPreferencesViewModel::class.java]
    }

    @Test
    fun `sem conta ativa a tela nao mostra preferencia de ninguem`() = runTest(testDispatcher) {
        auth = FakeAuthGateway(initialAccount = null)
        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.uiState.value is NotificationPreferencesUiState.SignedOut)
    }

    @Test
    fun `trocar de conta limpa o estado da conta anterior`() = runTest(testDispatcher) {
        gateway.preferences = SocialNotificationPreferences(pushEnabled = true)
        val model = viewModel()
        advanceUntilIdle()
        assertTrue(model.uiState.value is NotificationPreferencesUiState.Loaded)

        auth.signOut()
        advanceUntilIdle()
        assertTrue(
            "sair da conta precisa limpar a preferência que estava na tela",
            model.uiState.value is NotificationPreferencesUiState.SignedOut
        )

        // B entra, e o que aparece é a preferência **de B**.
        gateway.preferences = SocialNotificationPreferences(pushEnabled = false)
        auth.nextOutcome = AuthOutcome.Success(accountB)
        auth.signIn(context)
        advanceUntilIdle()

        val loaded = model.uiState.value as NotificationPreferencesUiState.Loaded
        assertFalse("a tela de B não pode mostrar a preferência de A", loaded.preferences.pushEnabled)
    }

    @Test
    fun `resposta iniciada pela conta anterior chegando depois da troca e descartada`() =
        runTest(testDispatcher) {
            gateway.preferences = SocialNotificationPreferences(pushEnabled = true)
            val model = viewModel()
            advanceUntilIdle()

            // A leitura de A fica em voo.
            val gate = CompletableDeferred<Unit>()
            gateway.gate = gate
            model.loadPreferences()
            advanceUntilIdle()

            // B entra enquanto a resposta de A não voltou. A preferência de B é o oposto — é o
            // que torna as duas respostas distinguíveis quando as duas voltarem.
            auth.signOut()
            auth.nextOutcome = AuthOutcome.Success(accountB)
            gateway.gate = null
            gateway.preferences = SocialNotificationPreferences(pushEnabled = false)
            auth.signIn(context)
            advanceUntilIdle()

            // Agora a resposta de A chega.
            gate.complete(Unit)
            advanceUntilIdle()

            val loaded = model.uiState.value as NotificationPreferencesUiState.Loaded
            assertFalse(
                "a resposta da conta A não pode sobrescrever o estado da conta B",
                loaded.preferences.pushEnabled
            )
        }

    /** Um gateway cuja leitura pode ser segurada, para pôr uma resposta em voo. */
    private class GatedNotificationGateway : SocialNotificationGateway {
        var preferences = SocialNotificationPreferences(pushEnabled = true)
        var gate: CompletableDeferred<Unit>? = null

        override val isConfigured: Boolean = true

        override suspend fun registerDevice(
            deviceId: String,
            fcmToken: String
        ): Result<PushDeviceRegistrationDto> =
            Result.success(PushDeviceRegistrationDto(deviceId, "ANDROID", true, 0L, 0L))

        override suspend fun unregisterDevice(deviceId: String): Result<Unit> = Result.success(Unit)

        override suspend fun getPreferences(): Result<SocialNotificationPreferences> {
            val held = gate
            val snapshot = preferences
            held?.await()
            return Result.success(snapshot)
        }

        override suspend fun updatePreferences(
            pushEnabled: Boolean?,
            friendRequestReceived: Boolean?,
            friendRequestAccepted: Boolean?,
            challengeInvitationReceived: Boolean?,
            challengeStartingSoon: Boolean?,
            challengeEnded: Boolean?,
            workoutShareReceived: Boolean?
        ): Result<SocialNotificationPreferences> {
            preferences = preferences.copy(pushEnabled = pushEnabled ?: preferences.pushEnabled)
            return Result.success(preferences)
        }
    }
}
