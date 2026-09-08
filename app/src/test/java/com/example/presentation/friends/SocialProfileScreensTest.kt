package com.example.presentation.friends

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SharedProgress
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialProfileError
import com.example.presentation.account.FriendProfilePhase
import com.example.presentation.account.ProgressSharingPhase
import com.example.presentation.account.SocialProfileUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * As telas do perfil social, renderizadas de verdade (T17.2).
 *
 * O que só uma tela real prova:
 *
 * 1. **compartilhamento parcial não deixa rastro do que foi escondido.** Sem "Streak: privado",
 *    sem traço, sem placeholder — um espaço reservado contaria ao visitante a configuração de
 *    privacidade da outra pessoa;
 * 2. **"não compartilha" nunca vira "não treina"**;
 * 3. **o dono distingue "desligado" de "ligado, ainda sem dado"** — a informação que o amigo
 *    deliberadamente não recebe;
 * 4. **nenhuma tela mostra treino bruto, horário, carga, medida ou identidade privada.**
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialProfileScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------ perfil do amigo

    @Test
    fun `tudo compartilhado mostra os tres blocos`() {
        composeRule.setContent {
            FriendSocialProfileBody(
                uiState = ready(SharedProgress(level = 14, consistencyStreak = 4, weeklyWorkoutCount = 3))
            )
        }

        composeRule.onNodeWithText("Igor").assertIsDisplayed()
        composeRule.onNodeWithText("14").assertIsDisplayed()
        // "semanas", e nunca "dias": a consistência do Spark é semanal, e traduzi-la na UI social
        // faria o mesmo número significar outra coisa.
        composeRule.onNodeWithText("4 semanas").assertIsDisplayed()
        composeRule.onNodeWithText("3 treinos").assertIsDisplayed()
    }

    @Test
    fun `compartilhamento parcial nao deixa placeholder do que foi escondido`() {
        composeRule.setContent {
            FriendSocialProfileBody(uiState = ready(SharedProgress(level = 14)))
        }

        composeRule.onNodeWithText("14").assertIsDisplayed()
        // Nada sobre os campos ausentes: nem rótulo, nem "privado", nem traço.
        composeRule.onNodeWithText("🔥 Consistência").assertDoesNotExist()
        composeRule.onNodeWithText("🏋️ Esta semana").assertDoesNotExist()
        composeRule.onNodeWithText("privado", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("—").assertDoesNotExist()
    }

    @Test
    fun `sem nada compartilhado a tela nao diz que a pessoa nao treina`() {
        composeRule.setContent {
            FriendSocialProfileBody(
                uiState = SocialProfileUiState(
                    friendPhase = FriendProfilePhase.NoSharedProgress(profile(SharedProgress()))
                )
            )
        }

        composeRule.onNodeWithText(NO_SHARED_PROGRESS_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("não treina", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("0", substring = true).assertDoesNotExist()
    }

    @Test
    fun `perfil indisponivel nao explica qual das quatro razoes foi`() {
        composeRule.setContent {
            FriendSocialProfileBody(
                uiState = SocialProfileUiState(friendPhase = FriendProfilePhase.NotAvailable)
            )
        }

        composeRule.onNodeWithText("Perfil indisponível").assertIsDisplayed()
        // Nenhuma das quatro situações que o servidor responde igual pode aparecer nomeada.
        for (leak in listOf("não são mais amigos", "desativou", "não existe", "pendente")) {
            composeRule.onNodeWithText(leak, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `offline diz que os treinos continuam normais`() {
        composeRule.setContent {
            FriendSocialProfileBody(
                uiState = SocialProfileUiState(friendPhase = FriendProfilePhase.Offline)
            )
        }

        composeRule.onNodeWithText("Sem conexão").assertIsDisplayed()
        composeRule.onNodeWithText("continuam normais", substring = true).assertIsDisplayed()
    }

    @Test
    fun `o perfil nunca mostra treino bruto nem identidade privada`() {
        composeRule.setContent {
            FriendSocialProfileBody(
                uiState = ready(SharedProgress(level = 14, consistencyStreak = 4, weeklyWorkoutCount = 3))
            )
        }

        for (forbidden in listOf(
            "SPK-", "@", "uid", "Supino", "kg", "Segunda", "19:30", "Peito", "medida", "última"
        )) {
            composeRule.onNodeWithText(forbidden, substring = true, ignoreCase = true)
                .assertDoesNotExist()
        }
    }

    // ------------------------------------------------------------------ minhas configurações

    @Test
    fun `o dono distingue desligado de ligado sem dado`() {
        composeRule.setContent {
            ProgressSharingBody(
                uiState = SocialProfileUiState(
                    sharingPhase = ProgressSharingPhase.Ready,
                    settings = ProgressSharingSettings(
                        shareLevel = true,
                        shareWeeklyWorkoutCount = true
                    ),
                    availability = ProgressSharingAvailability(
                        level = SocialFieldAvailability.UNSUPPORTED,
                        consistencyStreak = SocialFieldAvailability.UNSUPPORTED,
                        weeklyWorkoutCount = SocialFieldAvailability.AVAILABLE,
                        highlightedAchievements = SocialFieldAvailability.UNSUPPORTED
                    )
                )
            )
        }

        composeRule.onNodeWithText("Nível").assertIsDisplayed()
        composeRule.onNodeWithText("Treinos da semana").assertIsDisplayed()
        composeRule.onNodeWithText("Disponível").assertIsDisplayed()
        // A frase que impede a promessa falsa: sincronizar **não** resolve para estes campos.
        // Três dos quatro campos estão nesse estado nesta versão.
        composeRule.onAllNodesWithText("Não disponível nesta versão").assertCountEquals(3)
        composeRule.onNodeWithText(PREVIEW_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `offline na tela de privacidade diz que nada foi alterado`() {
        composeRule.setContent {
            ProgressSharingBody(
                uiState = SocialProfileUiState(sharingPhase = ProgressSharingPhase.Offline)
            )
        }

        composeRule.onNodeWithText("Nada foi alterado.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `o aviso de uma escrita que falhou nao derruba os interruptores`() {
        composeRule.setContent {
            ProgressSharingBody(
                uiState = SocialProfileUiState(
                    sharingPhase = ProgressSharingPhase.Ready,
                    notice = SocialProfileError.NETWORK
                )
            )
        }

        composeRule.onNodeWithText("Nível").assertIsDisplayed()
        composeRule.onNodeWithText("nada foi alterado", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a previa vazia diz que ninguem ve nada — sem inventar zeros`() {
        composeRule.setContent {
            ProgressSharingBody(
                uiState = SocialProfileUiState(
                    sharingPhase = ProgressSharingPhase.Ready,
                    preview = profile(SharedProgress())
                )
            )
        }

        composeRule.onNodeWithText(PREVIEW_EMPTY_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `a previa mostra exatamente os campos que um amigo veria`() {
        composeRule.setContent {
            ProgressSharingBody(
                uiState = SocialProfileUiState(
                    sharingPhase = ProgressSharingPhase.Ready,
                    settings = ProgressSharingSettings(shareLevel = true, shareConsistencyStreak = true),
                    preview = profile(SharedProgress(weeklyWorkoutCount = 3))
                )
            )
        }

        composeRule.onNodeWithText("3 treinos").assertIsDisplayed()
        // Ligado sem dado não aparece na prévia — que é justamente o que ela existe para revelar.
        composeRule.onNodeWithText("🔥 Consistência").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ apoio

    private fun profile(progress: SharedProgress) = FriendSocialProfile(
        socialId = "social-b",
        displayName = "Igor",
        sharedProgress = progress
    )

    private fun ready(progress: SharedProgress) = SocialProfileUiState(
        friendPhase = FriendProfilePhase.Ready(profile(progress))
    )
}
