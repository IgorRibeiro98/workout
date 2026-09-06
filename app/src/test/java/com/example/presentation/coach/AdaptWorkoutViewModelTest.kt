package com.example.presentation.coach

import com.example.domain.ai.AiWorkoutAdaptationContextBuilder
import com.example.domain.ai.AiWorkoutAdaptationSource
import com.example.domain.ai.FakeAiCoachGateway
import com.example.domain.ai.WorkoutAdaptationTestData
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.ApplyWorkoutAdaptationResult
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.usecase.AdaptWorkoutUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O ViewModel só fala com o provider por evento explícito, e só escreve por confirmação.
 *
 * O que estes testes protegem: custo (um pedido = uma chamada), seleção individual sem escrita,
 * e saída correta de loading quando o provider falha.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptWorkoutViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val templateId = 7L

    private val contextBuilder = object : AiWorkoutAdaptationContextBuilder {
        override suspend fun build(templateId: Long) = AiWorkoutAdaptationSource(
            templateId = templateId,
            revision = WorkoutAdaptationTestData.REVISION,
            context = WorkoutAdaptationTestData.context()
        )
    }

    /** Grava o que foi pedido para aplicar, sem tocar em banco. */
    private class RecordingApply(
        private val result: ApplyWorkoutAdaptationResult =
            ApplyWorkoutAdaptationResult.Applied(templateId = 7L, appliedChanges = 1)
    ) {
        val applied = mutableListOf<Pair<WorkoutAdaptationDraft, Set<String>>>()

        suspend operator fun invoke(
            draft: WorkoutAdaptationDraft,
            selected: Set<String>
        ): ApplyWorkoutAdaptationResult {
            applied += draft to selected
            return result
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        gateway: FakeAiCoachGateway,
        apply: RecordingApply = RecordingApply()
    ) = AdaptWorkoutViewModel(
        adaptWorkout = AdaptWorkoutUseCase(contextBuilder, gateway),
        applyAdaptation = apply::invoke
    ).also { it.load(templateId) }

    private fun successGateway() = FakeAiCoachGateway(
        adaptationResponder = {
            AiWorkoutAdaptationGatewayResult.Success(
                WorkoutAdaptationTestData.response(
                    changes = listOf(
                        WorkoutAdaptationTestData.loadChange(),
                        WorkoutAdaptationTestData.restChange()
                    )
                )
            )
        }
    )

    private val loadChangeId = "${WorkoutAdaptationType.ADJUST_LOAD.name}:supino-reto-barra"
    private val restChangeId = "${WorkoutAdaptationType.ADJUST_REST.name}:supino-reto-barra"

    @Test
    fun `abrir a tela nao chama o provider`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AdaptWorkoutStatus.Idle, viewModel.uiState.value.status)
        assertEquals(0, gateway.adaptationCallCount)
    }

    @Test
    fun `um pedido gera exatamente uma chamada`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)

        viewModel.adapt()
        assertEquals(AdaptWorkoutStatus.Generating, viewModel.uiState.value.status)
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as AdaptWorkoutStatus.Draft
        assertEquals(2, status.draft.changes.size)
        assertEquals(1, gateway.adaptationCallCount)
    }

    @Test
    fun `toques repetidos durante a analise nao viram chamadas extras`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)

        repeat(10) { viewModel.adapt() }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("dez toques rápidos não podem virar dez chamadas", 1, gateway.adaptationCallCount)
    }

    @Test
    fun `analisar novamente exige uma nova acao explicita`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, gateway.adaptationCallCount)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, gateway.adaptationCallCount)
    }

    @Test
    fun `nenhuma mudanca vem pre-selecionada`() = runTest(dispatcher) {
        val viewModel = viewModel(successGateway())

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.selectedChangeIds.isEmpty())
        assertEquals(0, viewModel.uiState.value.selectedCount)
        assertFalse("sem seleção não há o que aplicar", viewModel.uiState.value.canApply)
    }

    @Test
    fun `aceitar e recusar mudancas e estado local`() = runTest(dispatcher) {
        val apply = RecordingApply()
        val viewModel = viewModel(successGateway(), apply)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleChange(loadChangeId)
        assertEquals(1, viewModel.uiState.value.selectedCount)
        viewModel.toggleChange(restChangeId)
        assertEquals(2, viewModel.uiState.value.selectedCount)
        viewModel.toggleChange(restChangeId)
        assertEquals(1, viewModel.uiState.value.selectedCount)

        assertTrue("selecionar não escreve nada", apply.applied.isEmpty())
    }

    @Test
    fun `aplicar leva somente as mudancas selecionadas`() = runTest(dispatcher) {
        val apply = RecordingApply()
        val viewModel = viewModel(successGateway(), apply)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.toggleChange(loadChangeId)
        viewModel.apply()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, apply.applied.size)
        assertEquals(setOf(loadChangeId), apply.applied.single().second)
        assertEquals(AdaptWorkoutStatus.Applied(1), viewModel.uiState.value.status)
    }

    @Test
    fun `aplicar sem selecao nao chama a aplicacao`() = runTest(dispatcher) {
        val apply = RecordingApply()
        val viewModel = viewModel(successGateway(), apply)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.apply()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(apply.applied.isEmpty())
        assertTrue(viewModel.uiState.value.status is AdaptWorkoutStatus.Draft)
    }

    @Test
    fun `descartar limpa proposta e selecao sem escrever nada`() = runTest(dispatcher) {
        val apply = RecordingApply()
        val viewModel = viewModel(successGateway(), apply)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.toggleChange(loadChangeId)
        viewModel.discard()

        assertEquals(AdaptWorkoutStatus.Idle, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.selectedChangeIds.isEmpty())
        assertTrue(apply.applied.isEmpty())
    }

    @Test
    fun `draft obsoleto vira recado e pede nova analise`() = runTest(dispatcher) {
        val apply = RecordingApply(result = ApplyWorkoutAdaptationResult.StaleDraft)
        val viewModel = viewModel(successGateway(), apply)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.toggleChange(loadChangeId)
        viewModel.apply()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as AdaptWorkoutStatus.Message
        assertTrue(status.text.contains("mudou depois"))
        assertTrue(status.text.contains("nada foi alterado"))
    }

    @Test
    fun `falha ao aplicar nao apresenta as mudancas como aplicadas`() = runTest(dispatcher) {
        val apply = RecordingApply(result = ApplyWorkoutAdaptationResult.Failure("O treino não existe mais."))
        val viewModel = viewModel(successGateway(), apply)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.toggleChange(loadChangeId)
        viewModel.apply()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as AdaptWorkoutStatus.Message
        assertTrue(status.text.startsWith("Nada foi alterado"))
    }

    @Test
    fun `limite de uso sai de loading sem nova tentativa`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = {
                AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.RATE_LIMITED, "quota")
            }
        )
        val viewModel = viewModel(gateway)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as AdaptWorkoutStatus.Message
        assertTrue(status.text.contains("limite de uso"))
        assertFalse(status.canRetry)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(1, gateway.adaptationCallCount)
    }

    @Test
    fun `timeout sai de loading e permite nova tentativa explicita`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = { AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.TIMEOUT) }
        )
        val viewModel = viewModel(gateway)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as AdaptWorkoutStatus.Message
        assertTrue(status.canRetry)
        assertTrue(status.text.contains("Nada foi alterado"))
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun `sem internet a mensagem diz que a edicao manual continua`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = { AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.NETWORK) }
        )
        val viewModel = viewModel(gateway)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as AdaptWorkoutStatus.Message
        assertTrue(status.text.contains("à mão"))
        assertTrue(status.isWarning)
    }

    @Test
    fun `sem Conta Spark a adaptacao convida a entrar, sem tratar isso como falha`() =
        runTest(dispatcher) {
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.AUTH_REQUIRED)
                }
            )
            val viewModel = viewModel(gateway)

            viewModel.adapt()
            dispatcher.scheduler.advanceUntilIdle()

            // Estado próprio: adaptar com IA precisa de conta, editar à mão não.
            assertEquals(AdaptWorkoutStatus.AuthRequired, viewModel.uiState.value.status)
        }

    @Test
    fun `nenhuma mudanca proposta vira estado proprio`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = {
                AiWorkoutAdaptationGatewayResult.Success(
                    WorkoutAdaptationTestData.response(
                        summary = "Seu treino está adequado ao histórico atual.",
                        changes = emptyList()
                    )
                )
            }
        )
        val viewModel = viewModel(gateway)

        viewModel.adapt()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as AdaptWorkoutStatus.NoChanges
        assertEquals("Seu treino está adequado ao histórico atual.", status.summary)
    }
}
