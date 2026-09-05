package com.example.domain.ai

import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseRecommendation
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.AiWorkoutContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structured output continua sendo entrada não confiável.
 *
 * A política desta fase é estrita: uma violação invalida a resposta inteira, e nenhum
 * `exerciseId` desconhecido é resolvido por aproximação de nome.
 */
class AiCoachResponseValidatorTest {

    private val context = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = 2),
        currentWorkout = AiWorkoutContext(
            templateName = "Treino A",
            exercises = listOf(
                AiPlannedExerciseContext(
                    exerciseId = "supino-reto-barra",
                    name = "Supino reto com barra"
                )
            )
        )
    )

    private fun validate(response: AiCoachResponse) =
        AiCoachResponseValidator.validate("req-1", context, response)

    private fun recommendation(
        type: String = AiRecommendationType.REVIEW_LOAD.name,
        exerciseId: String? = "supino-reto-barra",
        reason: String = "A carga não subiu nas últimas sessões.",
        confidence: Double = 0.8
    ) = AiCoachResponseRecommendation(type, exerciseId, reason, confidence)

    @Test
    fun `resposta valida vira conselho preservando o id canonico`() {
        val result = validate(
            AiCoachResponse(
                summary = "Progressão estável.",
                recommendations = listOf(recommendation())
            )
        )

        val valid = result as AiCoachValidation.Valid
        assertEquals("req-1", valid.advice.requestId)
        assertEquals("Progressão estável.", valid.advice.summary)
        val recommendation = valid.advice.recommendations.single()
        assertEquals(AiRecommendationType.REVIEW_LOAD, recommendation.type)
        assertEquals("supino-reto-barra", recommendation.exerciseId)
        assertEquals(0.8, recommendation.confidence, 0.0001)
    }

    @Test
    fun `recomendacao geral sem exercicio e aceita`() {
        val result = validate(
            AiCoachResponse(
                summary = "Dados insuficientes para uma conclusão específica.",
                recommendations = listOf(
                    recommendation(type = AiRecommendationType.GENERAL.name, exerciseId = null)
                )
            )
        )

        assertTrue(result is AiCoachValidation.Valid)
    }

    @Test
    fun `resposta sem recomendacao continua valida`() {
        val result = validate(AiCoachResponse(summary = "Sem sugestões desta vez."))
        val valid = result as AiCoachValidation.Valid
        assertTrue(valid.advice.recommendations.isEmpty())
    }

    @Test
    fun `summary vazio invalida a resposta`() {
        assertTrue(validate(AiCoachResponse(summary = "   ")) is AiCoachValidation.Invalid)
    }

    @Test
    fun `summary absurdamente longo invalida a resposta`() {
        val result = validate(
            AiCoachResponse(summary = "a".repeat(AiCoachResponseValidator.MAX_SUMMARY_LENGTH + 1))
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `tipo fora do conjunto permitido invalida a resposta`() {
        val result = validate(
            AiCoachResponse(
                summary = "Resumo.",
                recommendations = listOf(recommendation(type = "INCREASE_LOAD_NOW"))
            )
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `reason vazio invalida a resposta`() {
        val result = validate(
            AiCoachResponse(summary = "Resumo.", recommendations = listOf(recommendation(reason = " ")))
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `confidence fora de faixa ou nao numerico invalida a resposta`() {
        val invalidConfidences = listOf(
            -1.0,
            2.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        )

        invalidConfidences.forEach { confidence ->
            val result = validate(
                AiCoachResponse(
                    summary = "Resumo.",
                    recommendations = listOf(recommendation(confidence = confidence))
                )
            )
            assertTrue("confidence=$confidence deveria invalidar", result is AiCoachValidation.Invalid)
        }
    }

    @Test
    fun `exerciseId inexistente e rejeitado sem tentativa de adivinhacao por nome`() {
        val result = validate(
            AiCoachResponse(
                summary = "Resumo.",
                recommendations = listOf(recommendation(exerciseId = "invalid-id"))
            )
        )

        val invalid = result as AiCoachValidation.Invalid
        assertTrue(invalid.reason.contains("invalid-id"))
    }

    @Test
    fun `nome de exercicio usado como id e rejeitado`() {
        val result = validate(
            AiCoachResponse(
                summary = "Resumo.",
                recommendations = listOf(recommendation(exerciseId = "Supino reto com barra"))
            )
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `excesso de recomendacoes invalida a resposta`() {
        val many = List(AiCoachResponseValidator.MAX_RECOMMENDATIONS + 1) { recommendation() }
        val result = validate(AiCoachResponse(summary = "Resumo.", recommendations = many))
        assertTrue(result is AiCoachValidation.Invalid)
    }
}
