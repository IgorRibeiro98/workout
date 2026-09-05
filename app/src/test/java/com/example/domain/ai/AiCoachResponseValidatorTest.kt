package com.example.domain.ai

import com.example.domain.ai.AiCoachTestData.dataQuality
import com.example.domain.ai.AiCoachTestData.observation
import com.example.domain.ai.AiCoachTestData.recommendation
import com.example.domain.ai.AiCoachTestData.response
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.AiWorkoutContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structured output continua sendo entrada não confiável.
 *
 * A política desta fase é estrita: uma violação invalida a resposta inteira, nenhum `exerciseId`
 * desconhecido é resolvido por aproximação de nome, e o modelo não pode afirmar mais evidência
 * do que o app enviou.
 */
class AiCoachResponseValidatorTest {

    private fun contextWith(
        sessionsAnalyzed: Int = 5,
        ceiling: AiDataQualityLevel = AiDataQualityLevel.GOOD
    ) = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = sessionsAnalyzed),
        currentWorkout = AiWorkoutContext(
            templateName = "Treino A",
            exercises = listOf(
                AiPlannedExerciseContext(
                    exerciseId = "supino-reto-barra",
                    name = "Supino reto com barra"
                )
            )
        ),
        evidence = AiEvidenceContext(
            sessionsAnalyzed = sessionsAnalyzed,
            exercisesWithHistory = 1,
            maxDataQuality = ceiling
        )
    )

    private val context = contextWith()

    private fun validate(
        response: AiCoachResponse,
        context: AiCoachContext = this.context
    ) = AiCoachResponseValidator.validate("req-1", context, response)

    @Test
    fun `resposta valida vira conselho preservando o id canonico`() {
        val result = validate(
            response(
                summary = "Progressão estável.",
                positiveSignals = listOf(observation(exerciseId = "supino-reto-barra")),
                attentionPoints = listOf(observation(title = "Volume parado")),
                recommendations = listOf(recommendation()),
                dataQuality = dataQuality(AiDataQualityLevel.GOOD)
            )
        )

        val valid = result as AiCoachValidation.Valid
        assertEquals("req-1", valid.advice.requestId)
        assertEquals("Progressão estável.", valid.advice.summary)
        assertEquals("supino-reto-barra", valid.advice.positiveSignals.single().exerciseId)
        assertEquals("Volume parado", valid.advice.attentionPoints.single().title)
        val recommendation = valid.advice.recommendations.single()
        assertEquals(AiRecommendationType.REVIEW_LOAD, recommendation.type)
        assertEquals("supino-reto-barra", recommendation.exerciseId)
        assertEquals(0.8, recommendation.confidence, 0.0001)
        assertEquals("60 kg em 3 sessões consecutivas", recommendation.evidence)
        assertEquals(AiDataQualityLevel.GOOD, valid.advice.dataQuality.level)
        // A contagem exposta ao usuário é do app, não do texto do modelo.
        assertEquals(5, valid.advice.sessionsAnalyzed)
    }

    @Test
    fun `recomendacao geral sem exercicio e aceita`() {
        val result = validate(
            response(
                summary = "Dados insuficientes para uma conclusão específica.",
                recommendations = listOf(
                    recommendation(
                        type = AiRecommendationType.GENERAL.name,
                        exerciseId = null,
                        evidence = null
                    )
                )
            )
        )

        assertTrue(result is AiCoachValidation.Valid)
    }

    @Test
    fun `resposta sem recomendacao continua valida`() {
        val valid = validate(response(summary = "Sem sugestões desta vez.")) as AiCoachValidation.Valid
        assertTrue(valid.advice.recommendations.isEmpty())
    }

    @Test
    fun `summary vazio invalida a resposta`() {
        assertTrue(validate(response(summary = "   ")) is AiCoachValidation.Invalid)
    }

    @Test
    fun `summary absurdamente longo invalida a resposta`() {
        val result = validate(
            response(summary = "a".repeat(AiCoachResponseValidator.MAX_SUMMARY_LENGTH + 1))
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `tipo fora do conjunto permitido invalida a resposta`() {
        val result = validate(
            response(recommendations = listOf(recommendation(type = "APPLY_LOAD")))
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `reason vazio invalida a resposta`() {
        val result = validate(response(recommendations = listOf(recommendation(reason = " "))))
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
                response(recommendations = listOf(recommendation(confidence = confidence)))
            )
            assertTrue("confidence=$confidence deveria invalidar", result is AiCoachValidation.Invalid)
        }
    }

    @Test
    fun `confidence nos limites da faixa e aceito`() {
        listOf(0.0, 1.0).forEach { confidence ->
            val result = validate(
                response(recommendations = listOf(recommendation(confidence = confidence)))
            )
            assertTrue("confidence=$confidence deveria valer", result is AiCoachValidation.Valid)
        }
    }

    @Test
    fun `exerciseId inexistente e rejeitado sem tentativa de adivinhacao por nome`() {
        val result = validate(
            response(recommendations = listOf(recommendation(exerciseId = "invalid-id")))
        )

        val invalid = result as AiCoachValidation.Invalid
        assertTrue(invalid.reason.contains("invalid-id"))
    }

    @Test
    fun `nome de exercicio usado como id e rejeitado`() {
        val result = validate(
            response(recommendations = listOf(recommendation(exerciseId = "Supino reto com barra")))
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `recomendacao que depende de exercicio nao aceita exerciseId nulo`() {
        AiRecommendationType.entries.filter { it.requiresExercise }.forEach { type ->
            val result = validate(
                response(
                    recommendations = listOf(
                        recommendation(type = type.name, exerciseId = null, evidence = null)
                    )
                )
            )
            assertTrue("$type exige exerciseId", result is AiCoachValidation.Invalid)
        }
    }

    @Test
    fun `recomendacao sobre exercicio sem evidencia e rejeitada`() {
        val result = validate(
            response(recommendations = listOf(recommendation(evidence = "  ")))
        )

        val invalid = result as AiCoachValidation.Invalid
        assertTrue(invalid.reason.contains("evidence"))
    }

    @Test
    fun `excesso de recomendacoes invalida a resposta`() {
        val many = List(AiCoachResponseValidator.MAX_RECOMMENDATIONS + 1) { recommendation() }
        assertTrue(validate(response(recommendations = many)) is AiCoachValidation.Invalid)
    }

    @Test
    fun `observacao sem titulo ou descricao invalida a resposta`() {
        assertTrue(
            validate(response(positiveSignals = listOf(observation(title = " ")))) is AiCoachValidation.Invalid
        )
        assertTrue(
            validate(response(attentionPoints = listOf(observation(description = " ")))) is AiCoachValidation.Invalid
        )
    }

    @Test
    fun `observacao sobre exercicio inexistente invalida a resposta`() {
        val result = validate(
            response(attentionPoints = listOf(observation(exerciseId = "exercicio-inventado")))
        )
        assertTrue(result is AiCoachValidation.Invalid)
    }

    @Test
    fun `excesso de observacoes invalida a resposta`() {
        val many = List(AiCoachResponseValidator.MAX_OBSERVATIONS + 1) { observation() }
        assertTrue(validate(response(positiveSignals = many)) is AiCoachValidation.Invalid)
    }

    @Test
    fun `dataQuality ausente invalida a resposta`() {
        assertTrue(validate(response(dataQuality = null)) is AiCoachValidation.Invalid)
    }

    @Test
    fun `dataQuality com valor desconhecido invalida a resposta`() {
        val result = validate(response(dataQuality = dataQuality("EXCELLENT")))
        val invalid = result as AiCoachValidation.Invalid
        assertTrue(invalid.reason.contains("EXCELLENT"))
    }

    @Test
    fun `dataQuality acima da evidencia enviada e rejeitada`() {
        val poorContext = contextWith(sessionsAnalyzed = 1, ceiling = AiDataQualityLevel.LIMITED)

        val result = validate(
            response(
                summary = "Você está em franca evolução.",
                dataQuality = dataQuality(AiDataQualityLevel.GOOD)
            ),
            context = poorContext
        )

        val invalid = result as AiCoachValidation.Invalid
        assertTrue(invalid.reason.contains("GOOD"))
        assertTrue(invalid.reason.contains("LIMITED"))
    }

    @Test
    fun `dataQuality mais conservador que a evidencia e aceito`() {
        val result = validate(
            response(dataQuality = dataQuality(AiDataQualityLevel.INSUFFICIENT)),
            context = contextWith(sessionsAnalyzed = 6, ceiling = AiDataQualityLevel.GOOD)
        )

        val valid = result as AiCoachValidation.Valid
        assertEquals(AiDataQualityLevel.INSUFFICIENT, valid.advice.dataQuality.level)
    }

    @Test
    fun `sem historico o teto e INSUFFICIENT e a analise continua valida`() {
        val emptyContext = contextWith(sessionsAnalyzed = 0, ceiling = AiDataQualityLevel.INSUFFICIENT)

        val result = validate(
            response(
                summary = "Ainda não há sessões concluídas para avaliar evolução.",
                recommendations = listOf(
                    recommendation(
                        type = AiRecommendationType.GENERAL.name,
                        exerciseId = null,
                        evidence = null
                    )
                ),
                dataQuality = dataQuality(AiDataQualityLevel.INSUFFICIENT)
            ),
            context = emptyContext
        )

        val valid = result as AiCoachValidation.Valid
        assertEquals(0, valid.advice.sessionsAnalyzed)
        assertEquals(AiDataQualityLevel.INSUFFICIENT, valid.advice.dataQuality.level)
    }
}
