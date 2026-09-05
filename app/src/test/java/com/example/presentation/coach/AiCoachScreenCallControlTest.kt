package com.example.presentation.coach

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.ai.model.AiDataQualityLevel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Custo é requisito: uma análise custa uma chamada ao provider, e nada além do toque do usuário
 * pode disparar uma.
 *
 * O teste renderiza a tela de verdade e recompõe várias vezes trocando o estado; se existisse um
 * `LaunchedEffect`, um `init` ou qualquer gatilho implícito, o contador subiria sozinho.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AiCoachScreenCallControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val successState = AiCoachUiState.Success(
        summary = "Progressão consistente.",
        positiveSignals = emptyList(),
        attentionPoints = emptyList(),
        recommendations = emptyList(),
        dataQuality = AiDataQualityUi(
            level = AiDataQualityLevel.GOOD,
            label = "Dados suficientes",
            description = "Análise baseada nas últimas 6 sessões concluídas.",
            sessionsAnalyzed = 6
        )
    )

    @Test
    fun `abrir a tela e recompor nao solicita analise`() {
        var analyzeCount = 0
        var state by mutableStateOf<AiCoachUiState>(AiCoachUiState.Idle)

        composeRule.setContent {
            AiCoachScreenContent(
                uiState = state,
                onAnalyze = { analyzeCount++ },
                onNavigateBack = {}
            )
        }
        composeRule.waitForIdle()
        assertEquals("abrir a tela não pode chamar o provider", 0, analyzeCount)

        repeat(5) {
            state = AiCoachUiState.Loading
            composeRule.waitForIdle()
            state = successState
            composeRule.waitForIdle()
        }

        assertEquals("recomposição não pode chamar o provider", 0, analyzeCount)
    }

    @Test
    fun `um toque do usuario gera exatamente uma solicitacao`() {
        var analyzeCount = 0
        var state by mutableStateOf<AiCoachUiState>(AiCoachUiState.Idle)

        composeRule.setContent {
            AiCoachScreenContent(
                uiState = state,
                onAnalyze = { analyzeCount++ },
                onNavigateBack = {}
            )
        }

        composeRule.onNodeWithText("  Analisar meu treino").performClick()
        composeRule.waitForIdle()
        assertEquals(1, analyzeCount)

        // Enquanto a análise está em andamento o botão fica desabilitado: toques repetidos não
        // chegam ao ViewModel, e o guarda de `inFlight` cobre o que escapar.
        state = AiCoachUiState.Loading
        composeRule.waitForIdle()
        repeat(9) { composeRule.onNodeWithText("  Analisar meu treino").performClick() }
        composeRule.waitForIdle()

        assertEquals("dez toques rápidos não podem virar dez chamadas", 1, analyzeCount)
    }
}
