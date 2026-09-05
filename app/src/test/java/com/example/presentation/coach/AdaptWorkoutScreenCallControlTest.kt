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
import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Custo e segurança na tela: nenhuma recomposição chama o provider, e nenhuma recomposição
 * aplica mudança no treino.
 *
 * O teste renderiza a tela de verdade e troca o estado várias vezes; se existisse um
 * `LaunchedEffect` de adaptação ou um gatilho implícito de aplicação, os contadores subiriam
 * sozinhos.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AdaptWorkoutScreenCallControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val loadChange = WorkoutAdaptationChange(
        id = "ADJUST_LOAD:supino-reto-barra",
        type = WorkoutAdaptationType.ADJUST_LOAD,
        exerciseId = "supino-reto-barra",
        exerciseName = "Supino reto com barra",
        currentValue = WorkoutAdaptationValue.Load(60f),
        suggestedValue = WorkoutAdaptationValue.Load(62.5f),
        reason = "As últimas sessões foram concluídas com a carga planejada.",
        evidence = "60 kg em 3 sessões concluídas",
        confidence = 0.84
    )

    private val draft = WorkoutAdaptationDraft(
        requestId = "req-1",
        templateId = 7L,
        templateName = "Peito + Tríceps",
        sourceRevision = "rev-1",
        summary = "Seu treino está evoluindo de forma consistente.",
        dataQuality = AiCoachDataQuality(AiDataQualityLevel.GOOD, "Baseado em 3 sessões concluídas."),
        changes = listOf(loadChange)
    )

    private val readyState = AdaptWorkoutUiState(templateId = 7L)

    @Test
    fun `abrir a tela e recompor nao solicita adaptacao nem aplica nada`() {
        var adaptCount = 0
        var applyCount = 0
        var state by mutableStateOf(readyState)

        composeRule.setContent {
            AdaptWorkoutScreenContent(
                uiState = state,
                actions = AdaptWorkoutActions(
                    onAdapt = { adaptCount++ },
                    onApply = { applyCount++ }
                )
            )
        }
        composeRule.waitForIdle()
        assertEquals("abrir a tela não pode chamar o provider", 0, adaptCount)

        repeat(5) {
            state = readyState.copy(status = AdaptWorkoutStatus.Generating)
            composeRule.waitForIdle()
            state = readyState.copy(
                status = AdaptWorkoutStatus.Draft(draft),
                selectedChangeIds = setOf(loadChange.id)
            )
            composeRule.waitForIdle()
        }

        assertEquals("recomposição não pode chamar o provider", 0, adaptCount)
        assertEquals("recomposição não pode alterar o treino", 0, applyCount)
    }

    @Test
    fun `um toque do usuario gera exatamente uma solicitacao`() {
        var adaptCount = 0
        var state by mutableStateOf(readyState)

        composeRule.setContent {
            AdaptWorkoutScreenContent(
                uiState = state,
                actions = AdaptWorkoutActions(onAdapt = { adaptCount++ })
            )
        }

        composeRule.onNodeWithText("  Adaptar meu treino").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, adaptCount)

        // Enquanto analisa, o botão fica desabilitado: toques repetidos não chegam ao ViewModel.
        state = readyState.copy(status = AdaptWorkoutStatus.Generating)
        composeRule.waitForIdle()
        repeat(9) { composeRule.onNodeWithText("  Adaptar meu treino").performScrollTo().performClick() }
        composeRule.waitForIdle()

        assertEquals("dez toques rápidos não podem virar dez chamadas", 1, adaptCount)
    }

    @Test
    fun `sem nenhuma mudanca selecionada o botao aplicar nao dispara`() {
        var applyCount = 0

        composeRule.setContent {
            AdaptWorkoutScreenContent(
                uiState = readyState.copy(status = AdaptWorkoutStatus.Draft(draft)),
                actions = AdaptWorkoutActions(onApply = { applyCount++ })
            )
        }

        composeRule.onNodeWithText("Aplicar").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("nada selecionado, nada aplicado", 0, applyCount)
    }

    @Test
    fun `aplicar exige selecao e toque explicito`() {
        var applyCount = 0
        var toggled = mutableListOf<String>()

        composeRule.setContent {
            AdaptWorkoutScreenContent(
                uiState = readyState.copy(
                    status = AdaptWorkoutStatus.Draft(draft),
                    selectedChangeIds = setOf(loadChange.id)
                ),
                actions = AdaptWorkoutActions(
                    onToggleChange = { toggled += it },
                    onApply = { applyCount++ }
                )
            )
        }
        composeRule.waitForIdle()
        assertEquals("mostrar o rascunho não aplica nada", 0, applyCount)

        composeRule.onNodeWithText("Aplicar 1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, applyCount)
    }

    @Test
    fun `o card mostra antes e depois e a evidencia`() {
        composeRule.setContent {
            AdaptWorkoutScreenContent(
                uiState = readyState.copy(status = AdaptWorkoutStatus.Draft(draft)),
                actions = AdaptWorkoutActions()
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("60 kg").assertExists()
        composeRule.onNodeWithText("62.5 kg").assertExists()
        composeRule.onNodeWithText("Base: 60 kg em 3 sessões concluídas").assertExists()
        composeRule.onNodeWithText("Nenhuma alteração selecionada").assertExists()
    }

    @Test
    fun `enquanto aplica nao ha botao de confirmar`() {
        var applyCount = 0

        composeRule.setContent {
            AdaptWorkoutScreenContent(
                uiState = readyState.copy(
                    status = AdaptWorkoutStatus.Applying(draft),
                    selectedChangeIds = setOf(loadChange.id)
                ),
                actions = AdaptWorkoutActions(onApply = { applyCount++ })
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Aplicando alterações...").assertExists()
        assertEquals(0, applyCount)
    }
}
