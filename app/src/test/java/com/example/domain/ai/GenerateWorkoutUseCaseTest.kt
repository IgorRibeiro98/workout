package com.example.domain.ai

import com.example.domain.ai.WorkoutGenerationTestData.candidate
import com.example.domain.ai.WorkoutGenerationTestData.context
import com.example.domain.ai.WorkoutGenerationTestData.exerciseResponse
import com.example.domain.ai.WorkoutGenerationTestData.preferences
import com.example.domain.ai.WorkoutGenerationTestData.response
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.GenerateWorkoutResult
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.usecase.GenerateWorkoutUseCase
import com.example.domain.engine.MuscleGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uma ação do usuário custa no máximo uma chamada ao provider — e às vezes nenhuma.
 *
 * O que estes testes protegem: o corte determinístico antes da chamada, a ausência de retry
 * automático e a tradução fiel dos erros do provider.
 */
class GenerateWorkoutUseCaseTest {

    private fun builder(
        context: AiWorkoutGenerationContext = context()
    ) = object : AiWorkoutGenerationContextBuilder {
        override suspend fun candidates(
            preferences: WorkoutGenerationPreferences
        ): List<AiCandidateExerciseContext> = context.candidateExercises

        override suspend fun build(preferences: WorkoutGenerationPreferences) = context
    }

    @Test
    fun `proposta valida vira rascunho para revisao`() = runTest {
        val gateway = FakeAiCoachGateway(
            generationResponder = { AiWorkoutGenerationGatewayResult.Success(response()) }
        )

        val result = GenerateWorkoutUseCase(builder(), gateway)(preferences())

        val draft = (result as GenerateWorkoutResult.Success).draft
        assertEquals("Peito e tríceps", draft.name)
        assertEquals(2, draft.exercises.size)
        assertEquals(1, gateway.generationCallCount)
    }

    @Test
    fun `sem candidatos o provider nao e chamado`() = runTest {
        val gateway = FakeAiCoachGateway(
            generationResponder = { AiWorkoutGenerationGatewayResult.Success(response()) }
        )

        val result = GenerateWorkoutUseCase(builder(context(candidates = emptyList())), gateway)(preferences())

        assertEquals(GenerateWorkoutResult.InsufficientCandidates, result)
        assertEquals("cota não pode ser gasta sem candidatos", 0, gateway.generationCallCount)
    }

    @Test
    fun `preferencias incompletas nao chamam o provider`() = runTest {
        val gateway = FakeAiCoachGateway(
            generationResponder = { AiWorkoutGenerationGatewayResult.Success(response()) }
        )

        val result = GenerateWorkoutUseCase(builder(), gateway)(
            preferences(focus = emptyList())
        ) as GenerateWorkoutResult.Failure

        assertEquals(AiCoachErrorKind.UNAVAILABLE, result.kind)
        assertEquals(0, gateway.generationCallCount)
    }

    @Test
    fun `o contexto enviado leva exatamente os candidatos permitidos`() = runTest {
        val gateway = FakeAiCoachGateway(
            generationResponder = { AiWorkoutGenerationGatewayResult.Success(response()) }
        )

        GenerateWorkoutUseCase(builder(), gateway)(preferences(focus = listOf(MuscleGroup.CHEST)))

        val sent = gateway.generationRequests.single()
        assertEquals(
            setOf("supino-reto-barra", "crucifixo-halteres"),
            sent.context.allowedExerciseIds
        )
        assertEquals(com.example.domain.ai.model.AiCoachRequestType.GENERATE_WORKOUT, sent.type)
        assertEquals(AiModelConfig.SCHEMA_VERSION, sent.schemaVersion)
    }

    @Test
    fun `resposta invalida nao dispara nova chamada automatica`() = runTest {
        val gateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Success(
                    response(exercises = listOf(exerciseResponse("INVENTADO", order = 1)))
                )
            }
        )

        val result = GenerateWorkoutUseCase(builder(), gateway)(preferences()) as GenerateWorkoutResult.Failure

        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
        assertEquals("nenhum retry automático", 1, gateway.generationCallCount)
    }

    @Test
    fun `id valido mas fora dos candidatos e rejeitado pelo caso de uso`() = runTest {
        val restricted = context(candidates = listOf(candidate("supino-reto-barra", "Supino reto com barra")))
        val gateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Success(
                    response(exercises = listOf(exerciseResponse("crucifixo-halteres", order = 1)))
                )
            }
        )

        val result = GenerateWorkoutUseCase(builder(restricted), gateway)(preferences()) as GenerateWorkoutResult.Failure

        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
        assertTrue(result.detail!!.contains("fora dos candidatos"))
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
                generationResponder = { AiWorkoutGenerationGatewayResult.Error(kind, "detalhe") }
            )

            val result = GenerateWorkoutUseCase(builder(), gateway)(preferences()) as GenerateWorkoutResult.Failure

            assertEquals(kind, result.kind)
            assertEquals(1, gateway.generationCallCount)
        }
    }

    @Test
    fun `modelo admitindo falta de candidatos vira estado proprio`() = runTest {
        val gateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Success(
                    response(name = "", exercises = emptyList(), insufficientCandidates = true)
                )
            }
        )

        val result = GenerateWorkoutUseCase(builder(), gateway)(preferences())

        assertEquals(GenerateWorkoutResult.InsufficientCandidates, result)
    }

    @Test
    fun `cada invocacao explicita gera exatamente uma chamada`() = runTest {
        val gateway = FakeAiCoachGateway(
            generationResponder = { AiWorkoutGenerationGatewayResult.Success(response()) }
        )
        val useCase = GenerateWorkoutUseCase(builder(), gateway)

        useCase(preferences())
        useCase(preferences())

        assertEquals(2, gateway.generationCallCount)
    }
}
