package com.example.domain.ai

import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseRecommendation
import com.example.domain.ai.model.AiCoachResult
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O caminho completo acima do provider, sem internet, Firebase, Gemini ou chave de API.
 */
class AnalyzeWorkoutUseCaseTest {

    private val context = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = 3),
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

    private val contextBuilder = object : AiCoachContextBuilder {
        override suspend fun build(): AiCoachContext = context
    }

    private fun useCase(gateway: FakeAiCoachGateway) =
        AnalyzeWorkoutUseCase(contextBuilder = contextBuilder, gateway = gateway)

    @Test
    fun `provider falso com resposta valida produz Success`() = runTest {
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachResponse(
                    summary = "Progressão consistente.",
                    recommendations = listOf(
                        AiCoachResponseRecommendation(
                            type = AiRecommendationType.KEEP_CURRENT_PLAN.name,
                            exerciseId = "supino-reto-barra",
                            reason = "A carga vem subindo de forma estável.",
                            confidence = 0.87
                        )
                    )
                )
            )
        }

        val result = useCase(gateway)() as AiCoachResult.Success

        assertEquals("Progressão consistente.", result.advice.summary)
        assertEquals("supino-reto-barra", result.advice.recommendations.single().exerciseId)
        assertEquals(1, gateway.callCount)
    }

    @Test
    fun `requisicao carrega requestId schemaVersion e tipo`() = runTest {
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(AiCoachResponse(summary = "Resumo."))
        }

        useCase(gateway)()

        val request = gateway.requests.single()
        assertTrue(request.requestId.isNotBlank())
        assertEquals(AiModelConfig.SCHEMA_VERSION, request.schemaVersion)
        assertEquals(AiCoachRequestType.ANALYZE_WORKOUT, request.type)
    }

    @Test
    fun `structured output valido desserializa e projeta`() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """
            {
              "summary": "Seu treino apresenta progressão consistente.",
              "recommendations": [
                {
                  "type": "GENERAL",
                  "exerciseId": null,
                  "reason": "Mantenha a estrutura atual.",
                  "confidence": 0.87
                }
              ]
            }
        """.trimIndent()

        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(json.decodeFromString<AiCoachResponse>(raw))
        }

        val result = useCase(gateway)() as AiCoachResult.Success
        assertEquals(AiRecommendationType.GENERAL, result.advice.recommendations.single().type)
    }

    @Test
    fun `resposta invalida vira INVALID_RESPONSE`() = runTest {
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(AiCoachResponse(summary = ""))
        }

        val result = useCase(gateway)() as AiCoachResult.Failure
        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
    }

    @Test
    fun `exerciseId inventado e rejeitado`() = runTest {
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachResponse(
                    summary = "Resumo.",
                    recommendations = listOf(
                        AiCoachResponseRecommendation(
                            type = AiRecommendationType.REVIEW_LOAD.name,
                            exerciseId = "invalid-id",
                            reason = "Motivo.",
                            confidence = 0.5
                        )
                    )
                )
            )
        }

        val result = useCase(gateway)() as AiCoachResult.Failure
        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
    }

    @Test
    fun `falhas do provider chegam sem retry`() = runTest {
        val kinds = listOf(
            AiCoachErrorKind.NETWORK,
            AiCoachErrorKind.TIMEOUT,
            AiCoachErrorKind.RATE_LIMITED,
            AiCoachErrorKind.UNAVAILABLE,
            AiCoachErrorKind.PROVIDER
        )

        kinds.forEach { kind ->
            val gateway = FakeAiCoachGateway { AiCoachGatewayResult.Error(kind) }
            val result = useCase(gateway)() as AiCoachResult.Failure

            assertEquals(kind, result.kind)
            assertEquals("$kind não pode gerar repetição automática", 1, gateway.callCount)
        }
    }

    @Test
    fun `falha ao montar contexto nao derruba o app`() = runTest {
        val failingBuilder = object : AiCoachContextBuilder {
            override suspend fun build(): AiCoachContext = throw IllegalStateException("sem banco")
        }
        val gateway = FakeAiCoachGateway { AiCoachGatewayResult.Success(AiCoachResponse("x")) }

        val result = AnalyzeWorkoutUseCase(failingBuilder, gateway)() as AiCoachResult.Failure

        assertEquals(AiCoachErrorKind.UNAVAILABLE, result.kind)
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun `telemetria registra apenas metadata tecnica`() = runTest {
        val recorded = mutableListOf<String>()
        val telemetry = object : AiCoachTelemetry {
            override fun onRequestFinished(
                requestId: String,
                type: AiCoachRequestType,
                model: String,
                schemaVersion: Int,
                durationMs: Long,
                result: String
            ) {
                recorded += "$type|$model|$schemaVersion|$result"
            }
        }
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(AiCoachResponse(summary = "Resumo."))
        }

        AnalyzeWorkoutUseCase(contextBuilder, gateway, telemetry)()

        assertEquals(
            listOf("ANALYZE_WORKOUT|${AiModelConfig.MODEL_NAME}|${AiModelConfig.SCHEMA_VERSION}|SUCCESS"),
            recorded
        )
    }
}
