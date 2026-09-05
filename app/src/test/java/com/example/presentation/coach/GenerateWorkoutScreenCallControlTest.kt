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
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.GeneratedWorkoutDraftExercise
import com.example.domain.engine.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Custo é requisito: uma geração custa uma chamada, e nada além do toque do usuário dispara uma.
 *
 * O teste renderiza a tela de verdade e recompõe várias vezes trocando o estado; se existisse um
 * `LaunchedEffect` de geração ou qualquer gatilho implícito, o contador subiria sozinho. O mesmo
 * vale para salvar: nenhuma recomposição pode confirmar um rascunho pelo usuário.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class GenerateWorkoutScreenCallControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val readyState = GenerateWorkoutUiState(
        focusMuscleGroups = listOf(MuscleGroup.CHEST),
        candidates = listOf(
            AiCandidateExerciseContext(
                exerciseId = "supino-reto-barra",
                name = "Supino reto com barra",
                muscleGroup = MuscleGroup.CHEST.displayName,
                equipment = "Barra"
            )
        )
    )

    private val draft = GeneratedWorkoutDraft(
        requestId = "req-1",
        name = "Peito e tríceps",
        explanation = "O supino vem primeiro por ser o movimento mais exigente.",
        exercises = listOf(
            GeneratedWorkoutDraftExercise(
                exerciseId = "supino-reto-barra",
                name = "Supino reto com barra",
                sortOrder = 0,
                sets = 4,
                minReps = 8,
                maxReps = 12,
                restSeconds = 120,
                reason = "Movimento principal do treino."
            )
        )
    )

    @Test
    fun `abrir a tela e recompor nao solicita geracao nem salvamento`() {
        var generateCount = 0
        var saveCount = 0
        var state by mutableStateOf(readyState)

        composeRule.setContent {
            GenerateWorkoutScreenContent(
                uiState = state,
                actions = GenerateWorkoutActions(
                    onGenerate = { generateCount++ },
                    onSave = { saveCount++ }
                )
            )
        }
        composeRule.waitForIdle()
        assertEquals("abrir a tela não pode chamar o provider", 0, generateCount)

        repeat(5) {
            state = readyState.copy(status = GenerateWorkoutStatus.Generating)
            composeRule.waitForIdle()
            state = readyState.copy(status = GenerateWorkoutStatus.Draft(draft))
            composeRule.waitForIdle()
        }

        assertEquals("recomposição não pode chamar o provider", 0, generateCount)
        assertEquals("recomposição não pode salvar treino", 0, saveCount)
    }

    @Test
    fun `um toque do usuario gera exatamente uma solicitacao`() {
        var generateCount = 0
        var state by mutableStateOf(readyState)

        composeRule.setContent {
            GenerateWorkoutScreenContent(
                uiState = state,
                actions = GenerateWorkoutActions(onGenerate = { generateCount++ })
            )
        }

        composeRule.onNodeWithText("  Gerar treino").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, generateCount)

        // Enquanto gera, o botão fica desabilitado: toques repetidos não chegam ao ViewModel, e o
        // guarda de `inFlight` cobre o que escapar.
        state = readyState.copy(status = GenerateWorkoutStatus.Generating)
        composeRule.waitForIdle()
        repeat(9) { composeRule.onNodeWithText("  Gerar treino").performScrollTo().performClick() }
        composeRule.waitForIdle()

        assertEquals("dez toques rápidos não podem virar dez chamadas", 1, generateCount)
    }

    @Test
    fun `sem candidatos o botao nao dispara chamada`() {
        var generateCount = 0

        composeRule.setContent {
            GenerateWorkoutScreenContent(
                uiState = GenerateWorkoutUiState(),
                actions = GenerateWorkoutActions(onGenerate = { generateCount++ })
            )
        }

        composeRule.onNodeWithText("  Gerar treino").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("sem foco e sem candidatos não se chama o provider", 0, generateCount)
    }

    @Test
    fun `salvar exige o toque explicito no rascunho`() {
        var saveCount = 0
        var discardCount = 0

        composeRule.setContent {
            GenerateWorkoutScreenContent(
                uiState = readyState.copy(status = GenerateWorkoutStatus.Draft(draft)),
                actions = GenerateWorkoutActions(
                    onSave = { saveCount++ },
                    onDiscard = { discardCount++ }
                )
            )
        }
        composeRule.waitForIdle()
        assertEquals("mostrar o rascunho não pode salvar", 0, saveCount)

        composeRule.onNodeWithText("Salvar treino").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, saveCount)

        composeRule.onNodeWithText("Descartar").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, discardCount)
    }

    @Test
    fun `enquanto salva o rascunho continua visivel sem botao de confirmar`() {
        var saveCount = 0

        composeRule.setContent {
            GenerateWorkoutScreenContent(
                uiState = readyState.copy(status = GenerateWorkoutStatus.Saving(draft)),
                actions = GenerateWorkoutActions(onSave = { saveCount++ })
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Salvando treino...").assertExists()
        assertEquals(0, saveCount)
    }
}
