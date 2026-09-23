package com.example.presentation.profile

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthState
import com.example.domain.auth.SparkAccount
import com.example.presentation.account.AccountUiState
import com.example.presentation.assertTextFits
import com.example.presentation.assertWithinViewportWidth
import com.example.presentation.setContentWithFontScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O cabeçalho do Perfil mostra quem está conectado (T19.H2 / H2.2).
 *
 * Ele dizia "Atleta" e desenhava um boneco genérico mesmo com conta Google conectada — a conta
 * chegava à tela (`AccountUiState`) e nunca chegava ao cabeçalho.
 *
 * A identidade é **derivada** da sessão a cada composição, nunca guardada. É o que garante o
 * requisito de troca de conta sem estado velho: não existe lugar onde o nome ou a foto da conta
 * anterior possam sobreviver, porque não existe lugar.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w360dp-h740dp")
class AthleteIdentityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun signedIn(
        displayName: String? = null,
        email: String? = null,
        photoUrl: String? = null,
        uid: String = "uid-a"
    ) = AccountUiState(
        authState = AuthState.SignedIn(SparkAccount(uid = uid, displayName = displayName, email = email, photoUrl = photoUrl))
    )

    // ------------------------------------------------------------------ a política

    @Test
    fun `sem conta o cabecalho volta para Atleta, sem foto`() {
        val identity = athleteIdentityOf(null)
        assertEquals(DEFAULT_ATHLETE_NAME, identity.name)
        assertNull(identity.photoUrl)
    }

    @Test
    fun `com displayName o nome e o da conta`() {
        val identity = athleteIdentityOf(signedIn(displayName = "João Neto", email = "joao@example.com").account)
        assertEquals("João Neto", identity.name)
    }

    @Test
    fun `sem displayName cai no e-mail, e nunca num nome inventado a partir dele`() {
        val identity = athleteIdentityOf(signedIn(email = "joao.neto@example.com").account)
        assertEquals("joao.neto@example.com", identity.name)
    }

    @Test
    fun `sem displayName e sem e-mail volta para Atleta`() {
        assertEquals(DEFAULT_ATHLETE_NAME, athleteIdentityOf(signedIn().account).name)
    }

    @Test
    fun `displayName em branco nao vira nome`() {
        assertEquals("a@example.com", athleteIdentityOf(signedIn(displayName = "   ", email = "a@example.com").account).name)
    }

    @Test
    fun `photoUrl em branco e o mesmo que nao ter foto`() {
        assertNull(athleteIdentityOf(signedIn(photoUrl = "  ").account).photoUrl)
        assertEquals("https://example.com/a.png", athleteIdentityOf(signedIn(photoUrl = "https://example.com/a.png").account).photoUrl)
    }

    @Test
    fun `o uid nunca e o nome`() {
        // Identidade técnica é o uid; nome é apresentação. Misturar os dois exporia a identidade
        // da conta numa tela que não fala sobre ela.
        val identity = athleteIdentityOf(signedIn(uid = "firebase-uid-abcdef").account)
        org.junit.Assert.assertFalse(identity.name.contains("firebase-uid-abcdef"))
    }

    // ------------------------------------------------------------------ na tela

    @Test
    fun `entrar, sair e entrar com outra conta troca o nome na hora`() {
        var account by mutableStateOf(signedIn(displayName = "João Neto", uid = "uid-a"))
        composeRule.setContentWithFontScale(1.0f) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                AthleteHeaderCard(
                    uiState = ProfileUiState(level = 4, totalXp = 4350),
                    identity = athleteIdentityOf(account.account)
                )
            }
        }
        composeRule.onNodeWithText("João Neto").assertIsDisplayed()

        // Logout: a identidade some no mesmo instante.
        account = AccountUiState(authState = AuthState.SignedOut)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("João Neto").assertDoesNotExist()
        composeRule.onNodeWithText(DEFAULT_ATHLETE_NAME).assertIsDisplayed()

        // Outra conta: o nome dela, e nada da anterior.
        account = signedIn(displayName = "Maria Souza", uid = "uid-b")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Maria Souza").assertIsDisplayed()
        composeRule.onNodeWithText("João Neto").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `um nome longo nao empurra o nivel para fora do cartao em 320dp`() {
        composeRule.setContentWithFontScale(1.3f) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                AthleteHeaderCard(
                    uiState = ProfileUiState(level = 4, totalXp = 4350),
                    identity = AthleteIdentity("Maria Fernanda de Albuquerque Nogueira", null)
                )
            }
        }
        composeRule.onNodeWithText("Nível 4").assertTextFits("nível")
        composeRule.onNodeWithText("Nível 4").assertWithinViewportWidth(320, "nível")
    }
}
