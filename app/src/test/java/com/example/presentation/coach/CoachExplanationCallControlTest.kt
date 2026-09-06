package com.example.presentation.coach

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachExplanationResult
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiRecommendationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Custo é requisito também na explicação contextual.
 *
 * As duas metades verificadas aqui:
 *
 * - a tela renderiza e recompõe muitas vezes **sem** pedir explicação;
 * - dez toques rápidos no mesmo "Por quê?" viram uma solicitação só.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@OptIn(ExperimentalCoroutinesApi::class)
class CoachExplanationCallControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val successState = AiCoachUiState.Success(
        summary = "Progressão consistente.",
        positiveSignals = emptyList(),
        attentionPoints = emptyList(),
        recommendations = listOf(
            AiRecommendationUi(
                id = "REC:0",
                type = AiRecommendationType.REVIEW_LOAD,
                label = "Revisar carga",
                exerciseName = "Supino reto com barra",
                reason = "A carga não subiu.",
                evidence = "60 kg em 4 sessões",
                confidencePercent = 80
            )
        ),
        dataQuality = AiDataQualityUi(
            level = AiDataQualityLevel.GOOD,
            label = "Dados suficientes",
            description = "Análise baseada nas últimas 6 sessões concluídas.",
            sessionsAnalyzed = 6
        )
    )

    @Test
    fun `abrir a tela e recompor nao solicita explicacao`() {
        var explainCount = 0
        var state by mutableStateOf<AiCoachUiState>(AiCoachUiState.Idle)

        composeRule.setContent {
            AiCoachScreenContent(
                uiState = state,
                onAnalyze = {},
                onNavigateBack = {},
                canExplain = true,
                onExplain = { explainCount++ }
            )
        }
        composeRule.waitForIdle()

        repeat(5) {
            state = AiCoachUiState.Loading
            composeRule.waitForIdle()
            state = successState
            composeRule.waitForIdle()
        }

        assertEquals("recomposição não pode pedir explicação", 0, explainCount)
    }

    @Test
    fun `o toque passa o id do alvo e nao o texto exibido`() {
        val targets = mutableListOf<String>()
        composeRule.setContent {
            AiCoachScreenContent(
                uiState = successState,
                onAnalyze = {},
                onNavigateBack = {},
                canExplain = true,
                onExplain = { targets += it }
            )
        }

        composeRule.onNodeWithText("  Por quê?").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("REC:0"), targets)
    }

    @Test
    fun `sem Coach contextual nenhuma entrada de explicacao aparece`() {
        composeRule.setContent {
            AiCoachScreenContent(
                uiState = successState,
                onAnalyze = {},
                onNavigateBack = {},
                canExplain = false,
                onExplain = {}
            )
        }

        composeRule.onNodeWithText("  Por quê?").assertDoesNotExist()
    }

    @Test
    fun `dez toques durante uma explicacao em andamento viram uma solicitacao`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = CoachExplanationController(scope)
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        repeat(10) {
            controller.request {
                calls++
                gate.await()
                AiCoachExplanationResult.Failure(AiCoachErrorKind.UNAVAILABLE)
            }
        }
        scope.advanceUntilIdle()

        assertEquals("dez toques rápidos não podem virar dez solicitações", 1, calls)
        assertTrue(controller.state.value is CoachExplanationUiState.Loading)

        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertTrue(controller.state.value is CoachExplanationUiState.Message)
    }

    @Test
    fun `fechar durante o carregamento nao reabre a folha quando a resposta chega`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = CoachExplanationController(scope)
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        controller.request {
            calls++
            gate.await()
            AiCoachExplanationResult.TargetNotFound
        }
        scope.advanceUntilIdle()
        assertTrue(controller.state.value is CoachExplanationUiState.Loading)

        controller.dismiss()
        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertEquals(CoachExplanationUiState.Hidden, controller.state.value)
        assertEquals("fechar não dispara nem repete chamada", 1, calls)
    }

    @Test
    fun `fechar a folha volta ao estado oculto sem nova solicitacao`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = CoachExplanationController(scope)
        var calls = 0

        controller.request {
            calls++
            AiCoachExplanationResult.TargetNotFound
        }
        scope.advanceUntilIdle()
        controller.dismiss()

        assertEquals(1, calls)
        assertEquals(CoachExplanationUiState.Hidden, controller.state.value)
    }
}
