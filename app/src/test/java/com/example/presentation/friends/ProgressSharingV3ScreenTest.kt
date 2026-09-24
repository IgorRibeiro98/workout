package com.example.presentation.friends

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ProgressSharingField
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SharedProgress
import com.example.presentation.account.FriendProfilePhase
import com.example.presentation.account.ProgressSharingPhase
import com.example.presentation.account.SocialProfileUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Compartilhar progresso" V3 (T19.H3 §23/§24/§31) e o perfil do amigo com as estatísticas.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ProgressSharingV3ScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tag(field: ProgressSharingField) =
        "progress_sharing_switch_${progressSharingLabel(field)}"

    private fun render(
        settings: ProgressSharingSettings = ProgressSharingSettings(),
        onToggle: (ProgressSharingField, Boolean) -> Unit = { _, _ -> }
    ) {
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProgressSharingBody(
                    uiState = SocialProfileUiState(
                        sharingPhase = ProgressSharingPhase.Ready,
                        settings = settings
                    ),
                    onToggle = onToggle
                )
            }
        }
    }

    @Test
    fun `os tres grupos aparecem, com os quinze interruptores desligados por padrao`() {
        render()

        for (title in listOf("PROGRESSO GERAL", "ESTATÍSTICAS DE TREINO", "DETALHES DOS CHECK-INS")) {
            composeRule.onNodeWithText(title).performScrollTo()
        }
        for (field in ProgressSharingField.entries) {
            composeRule.onNodeWithTag(tag(field)).performScrollTo().assertIsOff()
        }
    }

    @Test
    fun `o grupo de check-in diz que vale para as publicacoes antigas e para quem`() {
        render()

        composeRule.onNodeWithText("inclusive nos antigos", substring = true).performScrollTo()
        composeRule.onNodeWithText("Squads", substring = true).performScrollTo()
    }

    @Test
    fun `tocar num interruptor novo emite exatamente aquele campo`() {
        val toggled = mutableListOf<Pair<ProgressSharingField, Boolean>>()
        render(onToggle = { field, value -> toggled += field to value })

        composeRule.onNodeWithTag(tag(ProgressSharingField.WORKOUT_TIME)).performScrollTo().performClick()
        composeRule.onNodeWithTag(tag(ProgressSharingField.WEEKLY_VOLUME)).performScrollTo().performClick()

        assertEquals(
            listOf(ProgressSharingField.WORKOUT_TIME to true, ProgressSharingField.WEEKLY_VOLUME to true),
            toggled
        )
    }

    @Test
    fun `Cargas so pode ser ligado com Exercicios e Series e repeticoes (H3 31)`() {
        render(settings = ProgressSharingSettings(shareWorkoutExercises = true))
        composeRule.onNodeWithTag(tag(ProgressSharingField.WORKOUT_WEIGHTS)).performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `com Exercicios e Series ligados, Cargas fica disponivel`() {
        render(
            settings = ProgressSharingSettings(shareWorkoutExercises = true, shareWorkoutSets = true)
        )
        composeRule.onNodeWithTag(tag(ProgressSharingField.WORKOUT_WEIGHTS)).performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `Cargas ja ligada continua desligavel mesmo sem as dependencias`() {
        render(settings = ProgressSharingSettings(shareWorkoutWeights = true))
        composeRule.onNodeWithTag(tag(ProgressSharingField.WORKOUT_WEIGHTS)).performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `a previa mostra as estatisticas de treino que o servidor devolveu`() {
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProgressSharingBody(
                    uiState = SocialProfileUiState(
                        sharingPhase = ProgressSharingPhase.Ready,
                        preview = FriendSocialProfile(
                            socialId = "s",
                            displayName = "Ana",
                            sharedProgress = SharedProgress(weeklyVolumeKg = 6060.0, totalWorkouts = 3)
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("6.060 kg").performScrollTo()
        composeRule.onNodeWithText("3 treinos").performScrollTo()
    }

    @Test
    fun `o perfil do amigo mostra so as estatisticas que vieram — nenhum zero inventado`() {
        composeRule.setContent {
            FriendSocialProfileBody(
                uiState = SocialProfileUiState(
                    friendPhase = FriendProfilePhase.Ready(
                        FriendSocialProfile(
                            socialId = "s",
                            displayName = "Bruno",
                            sharedProgress = SharedProgress(weeklyTrainingMinutes = 85, weeklyCompletedSets = 11)
                        )
                    )
                )
            )
        }

        composeRule.onNodeWithText("1 h 25 min").assertExists()
        composeRule.onNodeWithText("11 séries").assertExists()
        composeRule.onNodeWithText("Volume na semana").assertDoesNotExist()
        composeRule.onNodeWithText("Treinos no total").assertDoesNotExist()
    }
}
