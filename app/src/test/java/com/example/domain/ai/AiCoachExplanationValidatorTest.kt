package com.example.domain.ai

import com.example.domain.ai.CoachExplanationTestData.explanationResponse
import com.example.domain.ai.model.AiCoachContextOrigin
import com.example.domain.ai.model.AiCoachExplanationContext
import com.example.domain.ai.model.AiExplanationFact
import com.example.domain.ai.model.AiPlannedExerciseContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uma explicação também é entrada não confiável.
 *
 * O que a validação garante: o texto existe e cabe no contrato, as limitações não são vazias e
 * **nenhum `exerciseId` citado é inventado** — nem mesmo um id real do catálogo que não entrou
 * neste contexto.
 */
class AiCoachExplanationValidatorTest {

    private fun context(
        exercises: List<AiPlannedExerciseContext> = listOf(
            AiPlannedExerciseContext(exerciseId = "supino-reto-barra", name = "Supino reto com barra")
        )
    ) = AiCoachExplanationContext(
        origin = AiCoachContextOrigin.GENERATED_WORKOUT.name,
        contextId = "generation-1",
        subject = "Por que o treino foi montado assim",
        facts = listOf(AiExplanationFact("Objetivo pedido", "Hipertrofia")),
        workoutExercises = exercises
    )

    private fun validate(response: com.example.domain.ai.model.AiCoachExplanationResponse) =
        AiCoachResponseValidator.validateExplanation(context(), response)

    @Test
    fun `resposta completa e valida`() {
        val result = validate(
            explanationResponse(
                limitations = listOf("Este treino ainda não foi executado."),
                referencedExerciseIds = listOf("supino-reto-barra")
            )
        )

        val valid = result as AiCoachExplanationValidation.Valid
        assertEquals("Por que essa mudança?", valid.title)
        assertEquals(listOf("Este treino ainda não foi executado."), valid.limitations)
    }

    @Test
    fun `titulo vazio invalida`() {
        val result = validate(explanationResponse(title = "   "))
        assertTrue(result is AiCoachExplanationValidation.Invalid)
    }

    @Test
    fun `explicacao vazia invalida`() {
        val result = validate(explanationResponse(explanation = ""))
        assertTrue(result is AiCoachExplanationValidation.Invalid)
    }

    @Test
    fun `explicacao longa demais invalida`() {
        val result = validate(
            explanationResponse(
                explanation = "a".repeat(AiCoachResponseValidator.MAX_EXPLANATION_TEXT_LENGTH + 1)
            )
        )
        assertTrue(result is AiCoachExplanationValidation.Invalid)
    }

    @Test
    fun `exerciseId fora do contexto invalida a resposta inteira`() {
        val result = validate(
            explanationResponse(referencedExerciseIds = listOf("agachamento-livre"))
        )

        val invalid = result as AiCoachExplanationValidation.Invalid
        assertTrue(invalid.reason.contains("agachamento-livre"))
    }

    @Test
    fun `limitacao vazia invalida`() {
        val result = validate(explanationResponse(limitations = listOf("  ")))
        assertTrue(result is AiCoachExplanationValidation.Invalid)
    }

    @Test
    fun `limitacoes demais invalidam`() {
        val result = validate(
            explanationResponse(
                limitations = List(AiCoachResponseValidator.MAX_LIMITATIONS + 1) { "Limitação $it." }
            )
        )
        assertTrue(result is AiCoachExplanationValidation.Invalid)
    }

    @Test
    fun `limitacoes repetidas viram uma so`() {
        val result = validate(
            explanationResponse(limitations = listOf("Pouca evidência.", "Pouca evidência."))
        )

        val valid = result as AiCoachExplanationValidation.Valid
        assertEquals(listOf("Pouca evidência."), valid.limitations)
    }

    @Test
    fun `contexto de progresso nao aceita nenhum exerciseId`() {
        val progressContext = AiCoachExplanationContext(
            origin = AiCoachContextOrigin.PROFILE_PROGRESS.name,
            contextId = AiCoachExplanationContextBuilder.PROGRESS_CONTEXT_ID,
            subject = "Progressão",
            facts = listOf(AiExplanationFact("Nível atual", "5"))
        )

        val result = AiCoachResponseValidator.validateExplanation(
            progressContext,
            explanationResponse(referencedExerciseIds = listOf("supino-reto-barra"))
        )

        assertTrue(result is AiCoachExplanationValidation.Invalid)
    }
}
