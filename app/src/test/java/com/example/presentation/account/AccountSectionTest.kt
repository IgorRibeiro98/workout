package com.example.presentation.account

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthState
import com.example.domain.auth.SparkAccount
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A área de Conta Spark, renderizada de verdade.
 *
 * Dois requisitos que só uma tela real prova: a autenticação **não** começa por abrir o Perfil ou
 * por recompor, e o texto nunca promete backup/sync como se já existissem.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AccountSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val account = SparkAccount(
        uid = "uid-1",
        displayName = "João",
        email = "joao@example.com"
    )

    @Test
    fun `abrir a area de conta e recompor nao inicia autenticacao`() {
        var signInCount = 0
        var state by mutableStateOf(AccountUiState(isSignInAvailable = true))

        composeRule.setContent {
            AccountSection(
                uiState = state,
                onSignIn = { signInCount++ },
                onSignOut = {}
            )
        }
        composeRule.waitForIdle()
        assertEquals("renderizar não pode abrir seletor de contas", 0, signInCount)

        repeat(3) {
            state = state.copy(isSignInAvailable = true)
            composeRule.waitForIdle()
        }
        assertEquals(0, signInCount)

        state = AccountUiState(authState = AuthState.SignedIn(account), isSignInAvailable = true)
        composeRule.waitForIdle()
        assertEquals("refletir sessão restaurada não é autenticar", 0, signInCount)
    }

    @Test
    fun `deslogado oferece entrar e deixa claro que a conta e opcional`() {
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(isSignInAvailable = true),
                onSignIn = {},
                onSignOut = {}
            )
        }

        composeRule.onNodeWithText("Continuar com Google").assertExists()
        composeRule.onNodeWithText("Você pode continuar usando o Spark sem conta.").assertExists()
        composeRule.onNodeWithText("Entrar é opcional").assertExists()
    }

    @Test
    fun `o toque explicito e o unico caminho para autenticar`() {
        var signInCount = 0
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(isSignInAvailable = true),
                onSignIn = { signInCount++ },
                onSignOut = {}
            )
        }

        composeRule.onNodeWithText("Continuar com Google").performClick()

        assertEquals(1, signInCount)
    }

    @Test
    fun `entrando bloqueia o botao e mostra progresso`() {
        var signInCount = 0
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(
                    authState = AuthState.SigningIn,
                    isSignInAvailable = true
                ),
                onSignIn = { signInCount++ },
                onSignOut = {}
            )
        }

        composeRule.onNodeWithText("Entrando...").assertExists().assertIsNotEnabled()
        composeRule.onNodeWithText("Entrando...").performClick()
        assertEquals("toque durante o fluxo não pode iniciar outro", 0, signInCount)
    }

    @Test
    fun `logado mostra identidade e a saida, sem prometer sincronizacao`() {
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(
                    authState = AuthState.SignedIn(account),
                    isSignInAvailable = true
                ),
                onSignIn = {},
                onSignOut = {}
            )
        }

        composeRule.onNodeWithText("João").assertExists()
        composeRule.onNodeWithText("joao@example.com").assertExists()
        composeRule.onNodeWithText("Conectado").assertExists()
        composeRule.onNodeWithText("Sair da conta").assertExists()
        // Desde a T16.4 o backup existe e é ativado na seção logo abaixo. Sincronização entre
        // dispositivos continua sendo etapa futura, e esta área não pode prometer o contrário.
        composeRule.onNodeWithText("Sincronização entre dispositivos chega nas próximas etapas.")
            .assertExists()

        composeRule.onNodeWithText("Sincronizado").assertDoesNotExist()
        composeRule.onNodeWithText("Todos os seus dispositivos estão sincronizados")
            .assertDoesNotExist()
        composeRule.onNodeWithText("Seus dados estão totalmente protegidos").assertDoesNotExist()
    }

    @Test
    fun `conta sem nome, email e foto ainda renderiza`() {
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(
                    authState = AuthState.SignedIn(SparkAccount(uid = "uid-so-com-id")),
                    isSignInAvailable = true
                ),
                onSignIn = {},
                onSignOut = {}
            )
        }

        // O uid é o único campo garantido; nome, email e foto são opcionais de verdade.
        composeRule.onNodeWithText("Conta conectada").assertExists()
        composeRule.onNodeWithText("Sair da conta").assertExists()
    }

    @Test
    fun `sair chama a intencao uma vez`() {
        var signOutCount = 0
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(
                    authState = AuthState.SignedIn(account),
                    isSignInAvailable = true
                ),
                onSignIn = {},
                onSignOut = { signOutCount++ }
            )
        }

        composeRule.onNodeWithText("Sair da conta").performClick()

        assertEquals(1, signOutCount)
    }

    @Test
    fun `erro e recuperavel e convida a tentar de novo`() {
        var signInCount = 0
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(
                    authState = AuthState.Error(AuthError.NETWORK),
                    isSignInAvailable = true
                ),
                onSignIn = { signInCount++ },
                onSignOut = {}
            )
        }

        composeRule.onNodeWithText("Sem conexão para entrar agora. Seus treinos continuam aqui.")
            .assertExists()
        composeRule.onNodeWithText("Tentar novamente").performClick()

        assertEquals(1, signInCount)
    }

    @Test
    fun `cancelamento nao mostra erro`() {
        // Cancelar leva a SignedOut, e é assim que a tela precisa se comportar.
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(
                    authState = AuthState.SignedOut,
                    isSignInAvailable = true
                ),
                onSignIn = {},
                onSignOut = {}
            )
        }

        composeRule.onNodeWithText("Continuar com Google").assertExists()
        composeRule.onNodeWithText("Tentar novamente").assertDoesNotExist()
    }

    @Test
    fun `sem configuracao a tela nao oferece um botao que falharia`() {
        composeRule.setContent {
            AccountSection(
                uiState = AccountUiState(isSignInAvailable = false),
                onSignIn = {},
                onSignOut = {}
            )
        }

        composeRule.onNodeWithText("Continuar com Google").assertDoesNotExist()
        composeRule.onNodeWithText("Entrar com o Google não está configurado neste aplicativo.")
            .assertExists()
        composeRule.onNodeWithText("Você pode continuar usando o Spark sem conta.").assertExists()
    }
}
