package com.example.presentation.social

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import com.example.presentation.account.FriendsUiState
import com.example.presentation.account.SocialPhase
import com.example.presentation.account.SocialSection
import com.example.presentation.account.SocialUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O conteúdo social reorganizado por intenção (T19.1 §6.3): as entradas continuam sendo as mesmas
 * do Perfil de antes — só o agrupamento visual mudou. Este teste prova que os grupos existem e que
 * nenhuma entrada foi perdida na reorganização.
 *
 * A tela completa ([SocialHomeScreen]) exige um `SocialViewModel` real (Robolectric não simula o
 * `Application` inteiro aqui); o que se testa diretamente é [SocialSection] dentro de uma coluna
 * rolável — a mesma moldura que `SocialHomeScreen` usa de verdade, necessária aqui porque a lista
 * de entradas é mais alta do que a janela de teste.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialHomeScreenTest {

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
    fun `entradas sociais aparecem organizadas por intencao`() {
        var openedSquads = 0

        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SocialSection(
                    uiState = SocialUiState(phase = SocialPhase.Active(profile)),
                    friendsState = FriendsUiState(friendCount = 3, incomingCount = 1),
                    onOpenSquads = { openedSquads++ },
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
        }

        // Os grupos de intenção existem...
        composeRule.onNodeWithText("Pessoas").assertExists()
        composeRule.onNodeWithText("Comunidades").assertExists()
        composeRule.onNodeWithText("Compartilhar").assertExists()
        composeRule.onNodeWithText("Privacidade").assertExists()

        // ...e nenhuma entrada de antes desapareceu.
        composeRule.onNodeWithText("Feed").assertExists()
        composeRule.onNodeWithText("Amigos").assertExists()
        composeRule.onNodeWithText("Squads").assertExists()
        composeRule.onNodeWithText("Desafios").assertExists()
        composeRule.onNodeWithText("Treinos compartilhados").assertExists()
        composeRule.onNodeWithText("Compartilhar progresso").assertExists()
        composeRule.onNodeWithText("Notificações").assertExists()
        composeRule.onNodeWithText("Usuários bloqueados").assertExists()

        composeRule.onNodeWithText("Squads").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, openedSquads)
    }
}
