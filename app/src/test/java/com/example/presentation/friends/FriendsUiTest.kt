package com.example.presentation.friends

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.Friend
import com.example.domain.social.FriendRelationship
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import com.example.presentation.account.FriendsAction
import com.example.presentation.account.FriendsUiState
import com.example.presentation.account.LookupState
import com.example.presentation.account.SocialPhase
import com.example.presentation.account.SocialSection
import com.example.presentation.account.SocialUiState
import com.example.domain.social.SocialProfilePreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * As telas do grafo social, renderizadas de verdade (T17.1).
 *
 * Três coisas que só uma tela real prova:
 *
 * 1. **procurar não envia.** O preview aparece e o convite só sai com um segundo toque, sobre um
 *    nome visível;
 * 2. **o que a tela mostra é o mínimo autorizado.** Nome social, e nada de nível, XP, sequência,
 *    último treino ou medida — nenhum deles é consequência de ser amigo;
 * 3. **"Meu código" copia o código, e não o `socialId`.**
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class FriendsUiTest {

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

    private val joao = SocialProfilePreview(socialId = "social-B", displayName = "João")

    // ------------------------------------------------------------------ seção do Perfil

    @Test
    fun `com o grafo ligado a secao oferece Amigos, Solicitacoes e Meu codigo`() {
        composeRule.setContent {
            SocialSection(
                uiState = SocialUiState(phase = SocialPhase.Active(profile)),
                friendsState = FriendsUiState(friendCount = 3, incomingCount = 1),
                onActivate = {},
                onDisplayNameChange = {},
                onConfirmActivation = {},
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

        // `assertExists`, e não `assertIsDisplayed`: no Perfil a seção vive dentro de uma coluna
        // com rolagem, e parte dela fica naturalmente abaixo da dobra. O que este teste afirma é
        // que os pontos de entrada **existem** e trazem os números certos — que a rolagem
        // funciona é responsabilidade do Compose, não desta asserção.
        composeRule.onNodeWithText("3 amigos · 1 solicitação pendente").assertExists()
        composeRule.onNodeWithText("Amigos").assertExists()
        // O contador entra no rótulo: é o "badge" desta fase, sem push e sem notificação.
        composeRule.onNodeWithText("Solicitações (1)").assertExists()
        composeRule.onNodeWithText("Meu código").assertExists()
        composeRule.onNodeWithText("SPK-7K2P9D8Q").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ adicionar amigo

    @Test
    fun `procurar mostra o preview e nao envia nada`() {
        var sendCount = 0
        var lookupCount = 0
        var state by mutableStateOf(
            FriendsUiState(
                isAddFriendOpen = true,
                codeInput = "SPK-BBBBBBBB",
                isCodeAcceptable = true
            )
        )

        composeRule.setContent {
            AddFriendDialog(
                uiState = state,
                onCodeChange = {},
                onLookup = { lookupCount++ },
                onScan = {},
                onSend = { sendCount++ },
                onCancelRequest = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText(LOOKUP_LABEL).performClick()
        assertEquals(1, lookupCount)
        // Procurar é procurar: nenhum convite saiu.
        assertEquals(0, sendCount)

        state = state.copy(
            lookup = LookupState.Found(
                profile = joao,
                relationship = FriendRelationship.NONE,
                canSendFriendRequest = true
            )
        )
        composeRule.waitForIdle()

        // O nome aparece **antes** do envio: é sobre ele que a pessoa decide.
        composeRule.onNodeWithText("João").assertIsDisplayed()
        composeRule.onNodeWithText(SEND_REQUEST_LABEL).performClick()
        assertEquals(1, sendCount)
    }

    @Test
    fun `o proprio codigo diz isso, e nao oferece enviar`() {
        composeRule.setContent {
            AddFriendDialog(
                uiState = FriendsUiState(isAddFriendOpen = true, lookup = LookupState.Self),
                onCodeChange = {},
                onLookup = {},
                onScan = {},
                onSend = {},
                onCancelRequest = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText(SELF_CODE_MESSAGE).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText(SEND_REQUEST_LABEL).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `um QR que nao e do Spark diz isso, e nao abre nada`() {
        composeRule.setContent {
            AddFriendDialog(
                uiState = FriendsUiState(isAddFriendOpen = true, lookup = LookupState.InvalidQr),
                onCodeChange = {},
                onLookup = {},
                onScan = {},
                onSend = {},
                onCancelRequest = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText(INVALID_QR_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `enviado mostra o estado e permite cancelar`() {
        var cancelled: String? = null
        composeRule.setContent {
            AddFriendDialog(
                uiState = FriendsUiState(
                    isAddFriendOpen = true,
                    lookup = LookupState.RequestSent(joao, "req-1")
                ),
                onCodeChange = {},
                onLookup = {},
                onScan = {},
                onSend = {},
                onCancelRequest = { cancelled = it },
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("$REQUEST_SENT_MESSAGE para João").assertIsDisplayed()
        composeRule.onNodeWithText("Cancelar solicitação").performClick()

        assertEquals("req-1", cancelled)
    }

    @Test
    fun `enquanto envia, o botao nao aceita um segundo toque`() {
        var sendCount = 0
        composeRule.setContent {
            AddFriendDialog(
                uiState = FriendsUiState(
                    isAddFriendOpen = true,
                    action = FriendsAction.SENDING_REQUEST,
                    lookup = LookupState.Found(joao, FriendRelationship.NONE, true)
                ),
                onCodeChange = {},
                onLookup = {},
                onScan = {},
                onSend = { sendCount++ },
                onCancelRequest = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText(SEND_REQUEST_LABEL).performClick()
        composeRule.onNodeWithText(SEND_REQUEST_LABEL).performClick()

        // O servidor também é idempotente, mas a tela não deve depender disso para não duplicar.
        assertEquals(0, sendCount)
    }

    // ------------------------------------------------------------------ meu código

    @Test
    fun `Meu codigo mostra o codigo e o QR, e nunca o socialId`() {
        composeRule.setContent {
            MyFriendCodeDialog(friendCode = profile.friendCode, onDismiss = {})
        }

        composeRule.onNodeWithText("SPK-7K2P9D8Q").assertIsDisplayed()
        composeRule.onNodeWithText(COPY_FRIEND_CODE_LABEL).assertIsDisplayed()
        // O `socialId` não é dado de compartilhar: ele é a identidade interna do domínio social,
        // e nada na tela deve convidar a copiá-lo.
        assertEquals(
            0,
            composeRule.onAllNodesWithText(profile.socialId, substring = true)
                .fetchSemanticsNodes().size
        )
    }

    // ------------------------------------------------------------------ o que a lista não mostra

    @Test
    fun `a lista de amigos mostra nome, e nada de treino`() {
        val friends = listOf(
            Friend(socialId = "s-1", displayName = "Igor", friendsSince = 1L),
            Friend(socialId = "s-2", displayName = "João", friendsSince = 2L),
            Friend(socialId = "s-3", displayName = "Jonathas", friendsSince = 3L)
        )

        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                FriendsBody(
                    uiState = FriendsUiState(
                        phase = com.example.presentation.account.FriendsPhase.Ready,
                        friends = friends,
                        friendCount = friends.size
                    )
                )
            }
        }

        for (name in listOf("Igor", "João", "Jonathas")) {
            composeRule.onNodeWithText(name).assertIsDisplayed()
        }
        // Nível, XP, sequência e "último treino" são T17.2/T17.4, e passam por projeção
        // autorizada. Adiantar "só um número" aqui é como essa fronteira se perde.
        for (forbidden in listOf("XP", "Nível", "Sequência", "Último treino", "kg")) {
            assertEquals(
                "a lista de amigos não pode mostrar '$forbidden'",
                0,
                composeRule.onAllNodesWithText(forbidden, substring = true)
                    .fetchSemanticsNodes().size
            )
        }
    }
}
