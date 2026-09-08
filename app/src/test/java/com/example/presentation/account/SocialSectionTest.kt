package com.example.presentation.account

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.SocialError
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A seção social do Perfil, renderizada de verdade (T17.0).
 *
 * Três coisas que só uma tela real prova: nada é ativado por renderizar ou recompor, o texto não
 * promete o que a T17.0 não entrega (amigos, desafios, ranking, QR Code), e a desativação não
 * afirma que treino, histórico ou backup serão apagados.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val profile = SocialProfile(
        socialId = "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d",
        friendCode = "SPK-7K2P9D8Q",
        displayName = "Igor",
        status = SocialProfileStatus.ACTIVE,
        privacy = SocialPrivacySettings(),
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun `renderizar e recompor nao ativa recursos sociais`() {
        var activateCount = 0
        var confirmCount = 0
        var state by mutableStateOf(SocialUiState(phase = SocialPhase.NotEnabled))

        composeRule.setContent {
            Section(
                state = state,
                onActivate = { activateCount++ },
                onConfirmActivation = { confirmCount++ }
            )
        }
        composeRule.waitForIdle()

        repeat(3) {
            state = state.copy(suggestedDisplayName = "Igor")
            composeRule.waitForIdle()
        }

        assertEquals("renderizar não pode ativar Social", 0, activateCount)
        assertEquals(0, confirmCount)
    }

    @Test
    fun `deslogado manda entrar na conta e deixa claro que o resto nao depende disso`() {
        composeRule.setContent { Section(SocialUiState(phase = SocialPhase.SignedOut)) }

        composeRule.onNodeWithText("Recursos sociais").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Entre na Conta Spark acima para se conectar com amigos. " +
                "Treinar, ver histórico e fazer backup não dependem disso."
        ).assertIsDisplayed()
    }

    @Test
    fun `nao ativado oferece ativacao e diz que e opcional`() {
        var activateCount = 0
        composeRule.setContent {
            Section(SocialUiState(phase = SocialPhase.NotEnabled), onActivate = { activateCount++ })
        }

        composeRule.onNodeWithText(
            "Os recursos sociais são opcionais e ficam desativados até você ativar."
        ).assertIsDisplayed()

        composeRule.onNodeWithText("Ativar recursos sociais").performClick()
        assertEquals("o toque é o único caminho", 1, activateCount)
    }

    @Test
    fun `a confirmacao de ativacao diz o que sera criado e o que continua privado`() {
        composeRule.setContent {
            Section(
                SocialUiState(
                    phase = SocialPhase.NotEnabled,
                    isActivationSheetOpen = true,
                    displayNameInput = "Igor",
                    isDisplayNameAcceptable = true
                )
            )
        }

        composeRule.onNodeWithText(
            "Será criado:\n• um identificador social\n• um código de amigo\n• um nome social"
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Seu e-mail, treinos e medidas não ficam públicos.")
            .assertIsDisplayed()
    }

    @Test
    fun `ativo mostra nome, codigo e privacidade`() {
        composeRule.setContent { Section(SocialUiState(phase = SocialPhase.Active(profile))) }

        composeRule.onNodeWithText("Social ativo").assertIsDisplayed()
        composeRule.onNodeWithText("Igor").assertIsDisplayed()
        composeRule.onNodeWithText("SPK-7K2P9D8Q").assertIsDisplayed()
        composeRule.onNodeWithText("Descoberta: somente por código de amigo").assertIsDisplayed()
        composeRule.onNodeWithText("Editar nome").assertIsDisplayed()
        composeRule.onNodeWithText("Desativar").assertIsDisplayed()
    }

    /**
     * Sem o grafo ligado (`friendsState = null`), a seção continua exatamente a da T17.0.
     *
     * Isso não é uma formalidade: `friendGateway` pode ser `null` (build sem backend), e nesse
     * caso a seção **não pode** oferecer Amigos, Solicitações nem QR Code — o botão levaria a uma
     * tela que não tem como funcionar. O que a T17.1 acrescenta é testado em `FriendsUiTest`.
     */
    @Test
    fun `sem o grafo ligado a tela nao promete o que nao pode entregar`() {
        composeRule.setContent { Section(SocialUiState(phase = SocialPhase.Active(profile))) }

        for (promise in listOf(
            "Adicionar amigo",
            "Compartilhar código",
            "QR Code",
            "Desafios",
            "Ranking",
            "Buscar amigos"
        )) {
            assertEquals(
                "sem o grafo ligado, a tela não pode oferecer '$promise'",
                0,
                composeRule.onAllNodesWithText(promise, substring = true)
                    .fetchSemanticsNodes().size
            )
        }
    }

    @Test
    fun `desativar nao diz que treino ou historico serao apagados`() {
        composeRule.setContent {
            Section(
                SocialUiState(phase = SocialPhase.Active(profile), isConfirmingDisable = true)
            )
        }

        composeRule.onNodeWithText("Desativar recursos sociais?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Seu treino, histórico, backup e Conta Spark não serão apagados.",
            substring = true
        ).assertIsDisplayed()
        // E nenhum texto diz que amizades serão apagadas: amizades ainda não existem, e
        // documentar como implementado o comportamento futuro delas seria mentir nas duas
        // direções. ("Aceitar pedidos de amizade", a preferência que **existe**, continua ali —
        // por isso a verificação procura a frase de exclusão, e não a palavra solta.)
        val apagaAmizade = Regex("amiza[^.]*apagad|apagad[^.]*amiza", RegexOption.IGNORE_CASE)
        assertEquals(
            0,
            composeRule.onAllNodesWithText("amiza", substring = true)
                .fetchSemanticsNodes()
                .count { node ->
                    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                        ?.any { apagaAmizade.containsMatchIn(it.text) } == true
                }
        )
    }

    @Test
    fun `offline diz que nada foi alterado`() {
        composeRule.setContent {
            Section(SocialUiState(phase = SocialPhase.Offline(profile)))
        }

        // `assertExists`, e não `assertIsDisplayed`: fora do Perfil a seção é renderizada sem o
        // `verticalScroll` que a hospeda de verdade, então um estado alto (perfil + aviso + ação)
        // passa da altura da viewport do teste. O que se afirma aqui é o **conteúdo**; que ele
        // caiba na tela com rolagem é responsabilidade do Perfil, não desta seção.
        composeRule.onNodeWithText("Sem conexão").assertExists()
        // A frase que impede o usuário de achar que a alteração ficou pendente para depois.
        composeRule.onNodeWithText("nada foi alterado", substring = true).assertExists()
    }

    @Test
    fun `desativado explica que a identidade volta igual`() {
        composeRule.setContent {
            Section(
                SocialUiState(
                    phase = SocialPhase.Active(profile.copy(status = SocialProfileStatus.DISABLED))
                )
            )
        }

        composeRule.onNodeWithText("Social desativado").assertIsDisplayed()
        composeRule.onNodeWithText("voltam iguais quando você reativar", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Reativar recursos sociais").assertIsDisplayed()
    }

    @Test
    fun `sem backend configurado a secao nao aparece`() {
        composeRule.setContent { Section(SocialUiState(phase = SocialPhase.NotConfigured)) }

        assertEquals(
            0,
            composeRule.onAllNodesWithText(SOCIAL_SECTION_TITLE).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `erro de sessao nao sugere que a conta foi apagada`() {
        composeRule.setContent {
            Section(SocialUiState(phase = SocialPhase.Error(SocialError.AUTH_REQUIRED, profile)))
        }

        composeRule.onNodeWithText("Sua sessão precisa ser renovada", substring = true)
            .assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun Section(
        state: SocialUiState,
        onActivate: () -> Unit = {},
        onConfirmActivation: () -> Unit = {}
    ) {
        SocialSection(
            uiState = state,
            onActivate = onActivate,
            onDisplayNameChange = {},
            onConfirmActivation = onConfirmActivation,
            onCancelActivation = {},
            onEditName = {},
            onConfirmName = {},
            onCancelEditName = {},
            onFriendRequestsChange = {},
            onActivitySharingChange = {},
            onDisable = {},
            onConfirmDisable = {},
            onCancelDisable = {},
            onEnable = {},
            onRetry = {}
        )
    }
}
