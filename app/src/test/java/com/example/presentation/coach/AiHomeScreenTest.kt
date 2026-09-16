package com.example.presentation.coach

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.ai.model.AiCapability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AiHome (T19.1): o hub só navega, e uma capability negada não pode iniciar nenhuma ação de IA
 * através dele — a mesma regra que cada tela de destino já aplica sozinha (T19.0), agora também
 * antes do toque chegar lá.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AiHomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `abrir o hub e recompor nao navega para nenhuma capacidade`() {
        var analyzeCount = 0
        var generateCount = 0
        var adaptCount = 0
        var state by mutableStateOf<AiCapabilitiesUiState>(AiCapabilitiesUiState.Idle)

        composeRule.setContent {
            AiHomeScreenContent(
                capabilitiesState = state,
                onNavigateBack = {},
                onNavigateToAnalyze = { analyzeCount++ },
                onNavigateToGenerate = { generateCount++ },
                onNavigateToAdaptEntry = { adaptCount++ }
            )
        }
        composeRule.waitForIdle()

        repeat(3) {
            state = AiCapabilitiesUiState.Loading
            composeRule.waitForIdle()
            state = AiCapabilitiesUiState.Loaded(setOf(AiCapability.AI_ANALYZE_WORKOUT))
            composeRule.waitForIdle()
        }

        assertEquals("abrir o hub não pode navegar sozinho", 0, analyzeCount)
        assertEquals(0, generateCount)
        assertEquals(0, adaptCount)
    }

    @Test
    fun `capability permitida navega com um toque`() {
        var analyzeCount = 0
        val state = AiCapabilitiesUiState.Loaded(setOf(AiCapability.AI_ANALYZE_WORKOUT))

        composeRule.setContent {
            AiHomeScreenContent(
                capabilitiesState = state,
                onNavigateBack = {},
                onNavigateToAnalyze = { analyzeCount++ },
                onNavigateToGenerate = {},
                onNavigateToAdaptEntry = {}
            )
        }

        composeRule.onNodeWithText("Analisar meu treino").performClick()
        assertEquals(1, analyzeCount)
    }

    @Test
    fun `capability negada nao navega e mostra o aviso`() {
        var adaptCount = 0
        val state = AiCapabilitiesUiState.Loaded(allowed = emptySet())

        composeRule.setContent {
            AiHomeScreenContent(
                capabilitiesState = state,
                onNavigateBack = {},
                onNavigateToAnalyze = {},
                onNavigateToGenerate = {},
                onNavigateToAdaptEntry = { adaptCount++ }
            )
        }

        composeRule.onNodeWithText("Adaptar treino").performClick()
        composeRule.waitForIdle()

        assertEquals("capability negada não pode iniciar a ação através do hub", 0, adaptCount)
        composeRule.onNodeWithText("A adaptação de treino não está disponível para esta conta.")
            .assertExists()
    }

    @Test
    fun `carregando ou sem conta continua navegavel (tela de destino decide)`() {
        var generateCount = 0

        composeRule.setContent {
            AiHomeScreenContent(
                capabilitiesState = AiCapabilitiesUiState.SignedOut,
                onNavigateBack = {},
                onNavigateToAnalyze = {},
                onNavigateToGenerate = { generateCount++ },
                onNavigateToAdaptEntry = {}
            )
        }

        composeRule.onNodeWithText("Gerar treino").performClick()
        assertEquals(1, generateCount)
    }
}
