package com.example.presentation.coach

import com.example.domain.ai.AiCoachContextBuilder
import com.example.domain.ai.AiCoachTestData
import com.example.domain.ai.FakeAiCoachGateway
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseRecommendation
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O ViewModel só fala com o provider por evento explícito.
 *
 * O teste de repetição existe para o requisito de custo: recomposição, rotação ou toque duplo
 * não podem virar chamadas extras ao modelo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val context = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = 2),
        currentWorkout = AiWorkoutContext(
            templateName = "Treino A",
            exercises = listOf(
                AiPlannedExerciseContext(exerciseId = "supino-reto-barra", name = "Supino reto com barra")
            )
        ),
        evidence = AiEvidenceContext(
            sessionsAnalyzed = 6,
            exercisesWithHistory = 1,
            maxDataQuality = AiDataQualityLevel.GOOD
        )
    )

    private val contextBuilder = object : AiCoachContextBuilder {
        override suspend fun build(): AiCoachContext = context
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(gateway: FakeAiCoachGateway) = AiCoachViewModel(
        analyzeWorkout = AnalyzeWorkoutUseCase(contextBuilder, gateway),
        exerciseNameResolver = { id -> if (id == "supino-reto-barra") "Supino reto com barra" else null }
    )

    private fun successResponse() = AiCoachGatewayResult.Success(
        AiCoachTestData.response(
            summary = "Progressão consistente.",
            positiveSignals = listOf(
                AiCoachTestData.observation(
                    exerciseId = "supino-reto-barra",
                    title = "Frequência consistente",
                    description = "O supino apareceu em todas as sessões analisadas."
                )
            ),
            attentionPoints = listOf(
                AiCoachTestData.observation(
                    exerciseId = "supino-reto-barra",
                    title = "Carga sem alteração",
                    description = "A carga registrada permaneceu em 60 kg."
                )
            ),
            recommendations = listOf(
                AiCoachResponseRecommendation(
                    type = AiRecommendationType.REVIEW_LOAD.name,
                    exerciseId = "supino-reto-barra",
                    reason = "A carga está parada há três sessões.",
                    confidence = 0.9,
                    evidence = "60 kg em 3 sessões consecutivas"
                )
            ),
            dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.GOOD)
        )
    )

    @Test
    fun `estado inicial e Idle e nada e solicitado ao provider`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway { successResponse() }
        val viewModel = viewModel(gateway)

        assertEquals(AiCoachUiState.Idle, viewModel.uiState.value)
        assertEquals(0, gateway.callCount)
    }

    @Test
    fun `analisar produz Success com a recomendacao projetada`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway { successResponse() }
        val viewModel = viewModel(gateway)

        viewModel.analyze()
        assertEquals(AiCoachUiState.Loading, viewModel.uiState.value)

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Success
        assertEquals("Progressão consistente.", state.summary)
        val recommendation = state.recommendations.single()
        assertEquals(AiRecommendationType.REVIEW_LOAD, recommendation.type)
        assertEquals("Supino reto com barra", recommendation.exerciseName)
        assertEquals("60 kg em 3 sessões consecutivas", recommendation.evidence)
        assertEquals(90, recommendation.confidencePercent)
    }

    @Test
    fun `pontos positivos e de atencao sao projetados em secoes separadas`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeAiCoachGateway { successResponse() })

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Success
        val positive = state.positiveSignals.single()
        assertEquals("Frequência consistente", positive.title)
        assertEquals("Supino reto com barra", positive.exerciseName)
        val attention = state.attentionPoints.single()
        assertEquals("Carga sem alteração", attention.title)
        assertEquals("A carga registrada permaneceu em 60 kg.", attention.description)
    }

    @Test
    fun `a qualidade dos dados aparece na UI com a contagem do app`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeAiCoachGateway { successResponse() })

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Success
        assertEquals(AiDataQualityLevel.GOOD, state.dataQuality.level)
        assertEquals("Dados suficientes", state.dataQuality.label)
        assertEquals(6, state.dataQuality.sessionsAnalyzed)
    }

    @Test
    fun `conclusao forte com pouca evidencia nao chega na UI como analise valida`() = runTest(dispatcher) {
        val poorContext = context.copy(
            evidence = AiEvidenceContext(
                sessionsAnalyzed = 1,
                exercisesWithHistory = 1,
                maxDataQuality = AiDataQualityLevel.LIMITED
            )
        )
        val poorBuilder = object : AiCoachContextBuilder {
            override suspend fun build() = poorContext
        }
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "Você entrou em platô e precisa mudar tudo.",
                    dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.GOOD)
                )
            )
        }
        val viewModel = AiCoachViewModel(
            analyzeWorkout = AnalyzeWorkoutUseCase(poorBuilder, gateway),
            exerciseNameResolver = { null }
        )

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Error
        assertTrue(state.message.contains("Nada no seu treino foi alterado"))
    }

    @Test
    fun `usuario sem historico recebe analise com qualidade insuficiente`() = runTest(dispatcher) {
        val emptyContext = context.copy(
            evidence = AiEvidenceContext(
                sessionsAnalyzed = 0,
                exercisesWithHistory = 0,
                maxDataQuality = AiDataQualityLevel.INSUFFICIENT
            )
        )
        val emptyBuilder = object : AiCoachContextBuilder {
            override suspend fun build() = emptyContext
        }
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "Ainda não há sessões concluídas para avaliar evolução.",
                    dataQuality = AiCoachTestData.dataQuality(
                        level = AiDataQualityLevel.INSUFFICIENT,
                        description = ""
                    )
                )
            )
        }
        val viewModel = AiCoachViewModel(
            analyzeWorkout = AnalyzeWorkoutUseCase(emptyBuilder, gateway),
            exerciseNameResolver = { null }
        )

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Success
        assertEquals(AiDataQualityLevel.INSUFFICIENT, state.dataQuality.level)
        assertEquals(0, state.dataQuality.sessionsAnalyzed)
        // Sem descrição do modelo, o app diz com o que realmente contou.
        assertTrue(state.dataQuality.description.contains("Nenhuma sessão concluída"))
    }

    @Test
    fun `toques repetidos durante o loading nao geram novas chamadas`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeAiCoachGateway {
            gate.await()
            successResponse()
        }
        val viewModel = viewModel(gateway)

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        repeat(5) { viewModel.analyze() }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, gateway.callCount)
        assertEquals(AiCoachUiState.Loading, viewModel.uiState.value)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AiCoachUiState.Success)
        assertEquals(1, gateway.callCount)
    }

    @Test
    fun `sem conectividade a UI comunica que apenas o Coach precisa de internet`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway { AiCoachGatewayResult.Error(AiCoachErrorKind.NETWORK) }
        val viewModel = viewModel(gateway)

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Unavailable
        assertTrue(state.message.contains("internet"))
        assertTrue(state.message.contains("offline"))
    }

    @Test
    fun `Coach nao configurado vira Unavailable e nao erro generico`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway { AiCoachGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE) }
        val viewModel = viewModel(gateway)

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AiCoachUiState.Unavailable)
    }

    @Test
    fun `rate limit tem mensagem propria e nao convida a repetir`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway { AiCoachGatewayResult.Error(AiCoachErrorKind.RATE_LIMITED) }
        val viewModel = viewModel(gateway)

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Error
        assertTrue(state.message.contains("limite"))
        assertEquals(false, state.canRetry)
        assertEquals(1, gateway.callCount)
    }

    @Test
    fun `timeout vira estado de erro com nova tentativa manual`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway { AiCoachGatewayResult.Error(AiCoachErrorKind.TIMEOUT) }
        val viewModel = viewModel(gateway)

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Error
        assertTrue(state.canRetry)

        // A nova tentativa é do usuário: só o segundo toque chama o provider de novo.
        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, gateway.callCount)
    }

    @Test
    fun `resposta invalida avisa que nada foi alterado`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(AiCoachResponse(summary = ""))
        }
        val viewModel = viewModel(gateway)

        viewModel.analyze()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AiCoachUiState.Error
        assertTrue(state.message.contains("Nada no seu treino foi alterado"))
    }
}
