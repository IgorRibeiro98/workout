package com.example.domain.ai

import com.example.domain.ai.WorkoutAdaptationTestData.context
import com.example.domain.ai.WorkoutAdaptationTestData.loadChange
import com.example.domain.ai.WorkoutAdaptationTestData.response
import com.example.domain.ai.model.AdaptWorkoutResult
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.usecase.AdaptWorkoutUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uma ação de adaptação custa no máximo uma chamada, e nenhuma proposta escapa da validação.
 */
class AdaptWorkoutUseCaseTest {

    private fun builder(
        context: AiWorkoutAdaptationContext? = context()
    ) = object : AiWorkoutAdaptationContextBuilder {
        override suspend fun build(templateId: Long): AiWorkoutAdaptationSource? = context?.let {
            AiWorkoutAdaptationSource(
                templateId = templateId,
                revision = WorkoutAdaptationTestData.REVISION,
                context = it
            )
        }
    }

    @Test
    fun `proposta valida vira rascunho`() = runTest {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = { AiWorkoutAdaptationGatewayResult.Success(response()) }
        )

        val result = AdaptWorkoutUseCase(builder(), gateway)(7L)

        val draft = (result as AdaptWorkoutResult.Success).draft
        assertEquals(7L, draft.templateId)
        assertEquals(WorkoutAdaptationTestData.REVISION, draft.sourceRevision)
        assertEquals(1, draft.changes.size)
        assertEquals(1, gateway.adaptationCallCount)
    }

    @Test
    fun `o contexto enviado leva o treino e os tipos autorizados`() = runTest {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = { AiWorkoutAdaptationGatewayResult.Success(response()) }
        )

        AdaptWorkoutUseCase(builder(), gateway)(7L)

        val sent = gateway.adaptationRequests.single()
        assertEquals(AiCoachRequestType.ADAPT_WORKOUT, sent.type)
        assertEquals(AiModelConfig.SCHEMA_VERSION, sent.schemaVersion)
        assertEquals(setOf("supino-reto-barra"), sent.context.templateExerciseIds)
        assertEquals(setOf("supino-reto-halteres"), sent.context.allowedReplacementIds)
    }

    @Test
    fun `treino inexistente nao chega ao provider`() = runTest {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = { AiWorkoutAdaptationGatewayResult.Success(response()) }
        )

        val result = AdaptWorkoutUseCase(builder(context = null), gateway)(7L) as AdaptWorkoutResult.Failure

        assertEquals(AiCoachErrorKind.UNAVAILABLE, result.kind)
        assertEquals(0, gateway.adaptationCallCount)
    }

    @Test
    fun `nenhuma mudanca proposta e resposta legitima`() = runTest {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = {
                AiWorkoutAdaptationGatewayResult.Success(
                    response(summary = "Seu treino está adequado ao histórico atual.", changes = emptyList())
                )
            }
        )

        val result = AdaptWorkoutUseCase(builder(), gateway)(7L)

        assertTrue(result is AdaptWorkoutResult.NoChanges)
        assertEquals(
            "Seu treino está adequado ao histórico atual.",
            (result as AdaptWorkoutResult.NoChanges).summary
        )
    }

    @Test
    fun `resposta invalida nao dispara nova chamada automatica`() = runTest {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = {
                AiWorkoutAdaptationGatewayResult.Success(
                    response(changes = listOf(loadChange(exerciseId = "INVENTADO")))
                )
            }
        )

        val result = AdaptWorkoutUseCase(builder(), gateway)(7L) as AdaptWorkoutResult.Failure

        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
        assertEquals("nenhum retry automático", 1, gateway.adaptationCallCount)
    }

    @Test
    fun `erros do provider chegam traduzidos e sem nova tentativa`() = runTest {
        listOf(
            AiCoachErrorKind.RATE_LIMITED,
            AiCoachErrorKind.TIMEOUT,
            AiCoachErrorKind.NETWORK,
            AiCoachErrorKind.UNAVAILABLE,
            AiCoachErrorKind.PROVIDER
        ).forEach { kind ->
            val gateway = FakeAiCoachGateway(
                adaptationResponder = { AiWorkoutAdaptationGatewayResult.Error(kind, "detalhe") }
            )

            val result = AdaptWorkoutUseCase(builder(), gateway)(7L) as AdaptWorkoutResult.Failure

            assertEquals(kind, result.kind)
            assertEquals(1, gateway.adaptationCallCount)
        }
    }

    @Test
    fun `cada invocacao explicita gera exatamente uma chamada`() = runTest {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = { AiWorkoutAdaptationGatewayResult.Success(response()) }
        )
        val useCase = AdaptWorkoutUseCase(builder(), gateway)

        useCase(7L)
        useCase(7L)

        assertEquals(2, gateway.adaptationCallCount)
    }
}
