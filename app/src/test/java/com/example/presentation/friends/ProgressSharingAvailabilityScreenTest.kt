package com.example.presentation.friends

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingField
import com.example.domain.social.ProgressSharingGroup
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SocialAvailabilityReason
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialSyncResult
import com.example.presentation.account.ProgressSharingPhase
import com.example.presentation.account.SharingDataSync
import com.example.presentation.account.SocialProfileUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Compartilhar progresso" com disponibilidade real (T19.H5 §8/§12/§13/§17/§29), no nível do que a
 * pessoa lê.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ProgressSharingAvailabilityScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tag(field: ProgressSharingField) =
        "progress_sharing_switch_${progressSharingLabel(field)}"

    private fun render(
        uiState: SocialProfileUiState,
        onSyncData: () -> Unit = {}
    ) {
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProgressSharingBody(uiState = uiState, onSyncData = onSyncData)
            }
        }
    }

    private fun ready(
        availability: ProgressSharingAvailability = ProgressSharingAvailability(),
        settings: ProgressSharingSettings = ProgressSharingSettings(),
        contractVersion: Int = 2,
        canSyncData: Boolean = true,
        dataSync: SharingDataSync = SharingDataSync.Idle
    ) = SocialProfileUiState(
        sharingPhase = ProgressSharingPhase.Ready,
        settings = settings,
        availability = availability,
        contractVersion = contractVersion,
        canSyncData = canSyncData,
        dataSync = dataSync
    )

    /** Todos os campos de perfil com o mesmo estado e o mesmo motivo. */
    private fun all(
        status: SocialFieldAvailability,
        reason: SocialAvailabilityReason? = null
    ) = ProgressSharingAvailability(
        level = status,
        consistencyStreak = status,
        weeklyWorkoutCount = status,
        highlightedAchievements = status,
        weeklyTrainingMinutes = status,
        weeklyCompletedSets = status,
        weeklyVolume = status,
        totalWorkouts = status,
        reasons = if (reason == null) emptyMap() else profileFields().associateWith { reason }
    )

    private fun profileFields() =
        ProgressSharingField.entries.filter { it.group != ProgressSharingGroup.CHECK_IN_DETAILS }

    // ------------------------------------------------------------------ uma frase por motivo (§12)

    @Test
    fun `cada motivo tem a sua frase — e nenhuma e a frase unica de antes`() {
        val cases = listOf(
            SocialAvailabilityReason.NO_SYNCED_WORKOUTS to
                "Sincronize seus treinos para disponibilizar este dado.",
            SocialAvailabilityReason.WEEK_TIME_ZONE_MISSING to
                "Precisamos atualizar sua configuração de semana",
            SocialAvailabilityReason.CONSISTENCY_PARAMETERS_MISSING to
                "Precisamos enviar sua meta semanal ao servidor",
            SocialAvailabilityReason.SOURCE_LIMIT_REACHED to
                "Este dado volta na próxima semana",
            SocialAvailabilityReason.UNKNOWN to
                "O servidor ainda não consegue calcular este dado."
        )
        for ((reason, phrase) in cases) {
            val hint = availabilityReasonHint(reason)
            assertEquals("$reason", true, hint.contains(phrase))
            assertEquals(
                "$reason não pode voltar à frase única",
                false,
                hint.contains("atualizado depois da sincronização")
            )
        }
    }

    @Test
    fun `o campo mostra o motivo que o servidor deu`() {
        render(
            ready(
                availability = all(SocialFieldAvailability.AVAILABLE).copy(
                    weeklyVolume = SocialFieldAvailability.UNAVAILABLE,
                    reasons = mapOf(
                        ProgressSharingField.WEEKLY_VOLUME to SocialAvailabilityReason.SOURCE_LIMIT_REACHED
                    )
                )
            )
        )

        composeRule.onNodeWithText("Este dado volta na próxima semana", substring = true)
            .performScrollTo()
        // Nada falta por falta de treino no servidor: sincronizar não é oferecido.
        composeRule.onNodeWithTag(SYNC_DATA_CARD_TAG).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ Sincronizar dados (§13/§17)

    @Test
    fun `sem treino no servidor a tela oferece Sincronizar dados, e o toque chega a ViewModel`() {
        var taps = 0
        render(
            ready(all(SocialFieldAvailability.UNAVAILABLE, SocialAvailabilityReason.NO_SYNCED_WORKOUTS)),
            onSyncData = { taps++ }
        )

        composeRule.onNodeWithTag(SYNC_DATA_CARD_TAG).performScrollTo()
        composeRule.onNodeWithText(SYNC_DATA_BUTTON).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, taps)
        // O "↻" e o sync são ações diferentes, e a tela diz isso.
        composeRule.onNodeWithText("o ↻ só relê o servidor", substring = true).performScrollTo()
        composeRule.onAllNodesWithText("Sincronize seus treinos para disponibilizar este dado.")
            .fetchSemanticsNodes().let { assertEquals(8, it.size) }
    }

    @Test
    fun `sem infraestrutura de sync no build, a acao nao e oferecida`() {
        render(
            ready(
                all(SocialFieldAvailability.UNAVAILABLE, SocialAvailabilityReason.NO_SYNCED_WORKOUTS),
                canSyncData = false
            )
        )

        composeRule.onNodeWithTag(SYNC_DATA_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun `sincronizando mostra carregamento e os interruptores esperam`() {
        render(
            ready(
                all(SocialFieldAvailability.UNAVAILABLE, SocialAvailabilityReason.NO_SYNCED_WORKOUTS),
                dataSync = SharingDataSync.Running
            )
        )

        composeRule.onNodeWithText(SYNC_DATA_RUNNING).performScrollTo()
        composeRule.onNodeWithText(SYNC_DATA_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(tag(ProgressSharingField.LEVEL)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(tag(ProgressSharingField.WORKOUT_NAME)).performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `sincronizou e o servidor continua sem treino — a tela diz isso, e nao pede de novo por pedir`() {
        render(
            ready(
                all(SocialFieldAvailability.UNAVAILABLE, SocialAvailabilityReason.NO_SYNCED_WORKOUTS),
                dataSync = SharingDataSync.Finished(SocialSyncResult.SYNCED, reread = true)
            )
        )

        composeRule.onNodeWithText("Sincronização concluída, mas nenhum treino concluído", substring = true)
            .performScrollTo()
        // O motivo de cada campo deixa de ser "sincronize" — já sincronizou.
        composeRule.onAllNodesWithText("Nenhum treino concluído chegou ao servidor ainda", substring = true)
            .fetchSemanticsNodes().let { assertEquals(8, it.size) }
        composeRule.onNodeWithText("Sincronize seus treinos para disponibilizar este dado.")
            .assertDoesNotExist()
    }

    @Test
    fun `sincronizou e o dado chegou — a confirmacao fica, e o botao sai`() {
        render(
            ready(
                all(SocialFieldAvailability.AVAILABLE),
                dataSync = SharingDataSync.Finished(SocialSyncResult.SYNCED, reread = true)
            )
        )

        composeRule.onNodeWithText("Dados sincronizados", substring = true).performScrollTo()
        composeRule.onNodeWithText(SYNC_DATA_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `offline o resultado diz que nada foi sincronizado — e o botao continua para tentar de novo`() {
        render(
            ready(
                all(SocialFieldAvailability.UNAVAILABLE, SocialAvailabilityReason.NO_SYNCED_WORKOUTS),
                dataSync = SharingDataSync.Finished(SocialSyncResult.OFFLINE, reread = false)
            )
        )

        composeRule.onNodeWithText("Sem conexão, então nada foi sincronizado", substring = true)
            .performScrollTo()
        composeRule.onNodeWithText(SYNC_DATA_BUTTON).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `cada desfecho do sync tem uma frase propria`() {
        val phrases = SocialSyncResult.entries.map { syncDataResultMessage(it, reread = true, stillMissing = true) }
        assertEquals(phrases.size, phrases.toSet().size)
    }

    // ------------------------------------------------------------------ servidor legado (§8)

    @Test
    fun `servidor legado — um aviso so, e nenhum interruptor que ele recusaria`() {
        render(
            ready(
                availability = ProgressSharingAvailability(
                    level = SocialFieldAvailability.AVAILABLE,
                    consistencyStreak = SocialFieldAvailability.AVAILABLE,
                    weeklyWorkoutCount = SocialFieldAvailability.AVAILABLE,
                    highlightedAchievements = SocialFieldAvailability.AVAILABLE,
                    weeklyTrainingMinutes = SocialFieldAvailability.UNSUPPORTED,
                    weeklyCompletedSets = SocialFieldAvailability.UNSUPPORTED,
                    weeklyVolume = SocialFieldAvailability.UNSUPPORTED,
                    totalWorkouts = SocialFieldAvailability.UNSUPPORTED,
                    reasons = ProgressSharingField.entries
                        .filter { it.group != ProgressSharingGroup.GENERAL }
                        .associateWith { SocialAvailabilityReason.LEGACY_BACKEND }
                ),
                contractVersion = 1
            )
        )

        for (field in ProgressSharingField.entries) {
            if (field.group == ProgressSharingGroup.GENERAL) {
                composeRule.onNodeWithTag(tag(field)).performScrollTo().assertIsEnabled()
            } else {
                composeRule.onNodeWithTag(tag(field)).assertDoesNotExist()
            }
        }
        composeRule.onNodeWithTag(LEGACY_BACKEND_NOTICE_TAG).performScrollTo()
        composeRule.onAllNodesWithText(LEGACY_BACKEND_MESSAGE, substring = true)
            .fetchSemanticsNodes().let { assertEquals(1, it.size) }
        // Não é "Ainda não disponível": sincronizar não resolveria.
        composeRule.onNodeWithText("Ainda não disponível").assertDoesNotExist()
        composeRule.onNodeWithTag(SYNC_DATA_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun `servidor v2 — nenhum aviso de servidor legado`() {
        render(ready(all(SocialFieldAvailability.AVAILABLE)))

        composeRule.onNodeWithTag(LEGACY_BACKEND_NOTICE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(tag(ProgressSharingField.WORKOUT_VOLUME)).performScrollTo()
    }

    // ------------------------------------------------------------------ Cargas (§29)

    @Test
    fun `Cargas ligada sem Exercicios e Series diz que esta sem efeito agora`() {
        render(
            ready(
                all(SocialFieldAvailability.AVAILABLE),
                settings = ProgressSharingSettings(shareWorkoutWeights = true, shareWorkoutExercises = true)
            )
        )

        composeRule.onNodeWithText(WEIGHTS_WITHOUT_EFFECT_NOTE).performScrollTo()
    }

    @Test
    fun `Cargas ligada com as duas dependencias nao mostra o aviso`() {
        render(
            ready(
                all(SocialFieldAvailability.AVAILABLE),
                settings = ProgressSharingSettings(
                    shareWorkoutWeights = true,
                    shareWorkoutExercises = true,
                    shareWorkoutSets = true
                )
            )
        )

        composeRule.onNodeWithText(WEIGHTS_WITHOUT_EFFECT_NOTE).assertDoesNotExist()
        composeRule.onNodeWithText("A carga aparece dentro de cada série", substring = true)
            .performScrollTo()
    }

    @Test
    fun `detalhes do check-in sao preferencia — sem disponibilidade, sempre configuraveis num servidor v2`() {
        render(ready(all(SocialFieldAvailability.UNAVAILABLE, SocialAvailabilityReason.NO_SYNCED_WORKOUTS)))

        for (field in ProgressSharingField.entries.filter { it.group == ProgressSharingGroup.CHECK_IN_DETAILS }) {
            if (field == ProgressSharingField.WORKOUT_WEIGHTS) continue // depende de Exercícios e Séries
            composeRule.onNodeWithTag(tag(field)).performScrollTo().assertIsEnabled()
        }
    }
}
