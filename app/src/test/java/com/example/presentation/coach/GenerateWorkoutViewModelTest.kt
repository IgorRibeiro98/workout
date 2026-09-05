package com.example.presentation.coach

import com.example.domain.ai.AiWorkoutGenerationContextBuilder
import com.example.domain.ai.FakeAiCoachGateway
import com.example.domain.ai.WorkoutGenerationTestData
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.SaveGeneratedWorkoutResult
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.usecase.GenerateWorkoutUseCase
import com.example.domain.engine.MuscleGroup
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
 * O que estes testes protegem: custo (um pedido = uma chamada), a ausência de persistência antes
 * do "Salvar treino" e a saída correta de loading em erro.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenerateWorkoutViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val context = WorkoutGenerationTestData.context()

    private val contextBuilder = object : AiWorkoutGenerationContextBuilder {
        override suspend fun candidates(
            preferences: WorkoutGenerationPreferences
        ): List<AiCandidateExerciseContext> = context.candidateExercises

        override suspend fun build(preferences: WorkoutGenerationPreferences): AiWorkoutGenerationContext =
            context
    }

    /**
     * Grava o que foi pedido para salvar, sem tocar em banco.
     *
     * A persistência real tem o seu próprio teste, contra o Room de verdade.
     */
    private class RecordingSave {
        val saved = mutableListOf<GeneratedWorkoutDraft>()

        suspend operator fun invoke(draft: GeneratedWorkoutDraft): SaveGeneratedWorkoutResult {
            saved += draft
            return SaveGeneratedWorkoutResult.Saved(templateId = 42L, name = draft.name)
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
        save: RecordingSave = RecordingSave()
    ) = GenerateWorkoutViewModel(
        generateWorkout = GenerateWorkoutUseCase(contextBuilder, gateway),
        saveGeneratedWorkout = save::invoke,
        listCandidates = { contextBuilder.candidates(it) }
    )

    private fun successGateway() = FakeAiCoachGateway(
        generationResponder = {
            AiWorkoutGenerationGatewayResult.Success(WorkoutGenerationTestData.response())
        }
    )

    private fun GenerateWorkoutViewModel.configureFocus() {
        toggleFocus(MuscleGroup.CHEST)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `abrir o ViewModel nao chama o provider`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)

        assertEquals(GenerateWorkoutStatus.Idle, viewModel.uiState.value.status)
        assertEquals(0, gateway.generationCallCount)

        viewModel.refreshCandidates()
        dispatcher.scheduler.advanceUntilIdle()

        // Carregar candidatos é leitura local: não custa chamada.
        assertEquals(0, gateway.generationCallCount)
    }

    @Test
    fun `mudar preferencia recalcula candidatos sem chamar o provider`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)

        viewModel.setGoal(com.example.domain.ai.model.WorkoutGoal.STRENGTH)
        viewModel.setDuration(45)
        viewModel.configureFocus()
        viewModel.toggleEquipment(EquipmentAvailability.DUMBBELL)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.candidates.size)
        assertEquals(0, gateway.generationCallCount)
    }

    @Test
    fun `sem foco escolhido o botao nao libera`() = runTest(dispatcher) {
        val viewModel = viewModel(successGateway())

        assertFalse(viewModel.uiState.value.canGenerate)
    }

    @Test
    fun `um pedido gera exatamente uma chamada`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)
        viewModel.configureFocus()

        viewModel.generate()
        assertEquals(GenerateWorkoutStatus.Generating, viewModel.uiState.value.status)
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as GenerateWorkoutStatus.Draft
        assertEquals("Peito e tríceps", status.draft.name)
        assertEquals(1, gateway.generationCallCount)
    }

    @Test
    fun `toques repetidos durante a geracao nao viram chamadas extras`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)
        viewModel.configureFocus()

        repeat(10) { viewModel.generate() }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("dez toques rápidos não podem virar dez chamadas", 1, gateway.generationCallCount)
    }

    @Test
    fun `gerar novamente exige uma nova acao explicita`() = runTest(dispatcher) {
        val gateway = successGateway()
        val viewModel = viewModel(gateway)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, gateway.generationCallCount)

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, gateway.generationCallCount)
    }

    @Test
    fun `descartar remove o rascunho e nao salva nada`() = runTest(dispatcher) {
        val save = RecordingSave()
        val viewModel = viewModel(successGateway(), save)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.discardDraft()

        assertEquals(GenerateWorkoutStatus.Idle, viewModel.uiState.value.status)
        assertTrue("descartar não pode persistir", save.saved.isEmpty())
    }

    @Test
    fun `remover exercicio do rascunho recompacta a ordem sem persistir`() = runTest(dispatcher) {
        val save = RecordingSave()
        val viewModel = viewModel(successGateway(), save)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.removeExerciseFromDraft("supino-reto-barra")

        val draft = (viewModel.uiState.value.status as GenerateWorkoutStatus.Draft).draft
        assertEquals(listOf("crucifixo-halteres"), draft.exercises.map { it.exerciseId })
        assertEquals(listOf(0), draft.exercises.map { it.sortOrder })
        assertTrue(save.saved.isEmpty())
    }

    @Test
    fun `salvar so acontece por acao explicita e usa o rascunho revisado`() = runTest(dispatcher) {
        val save = RecordingSave()
        val viewModel = viewModel(successGateway(), save)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("gerar não pode salvar", save.saved.isEmpty())

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, save.saved.size)
        val status = viewModel.uiState.value.status as GenerateWorkoutStatus.Saved
        assertEquals(42L, status.templateId)
    }

    @Test
    fun `salvar sem rascunho nao faz nada`() = runTest(dispatcher) {
        val save = RecordingSave()
        val viewModel = viewModel(successGateway(), save)

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(save.saved.isEmpty())
        assertEquals(GenerateWorkoutStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun `limite de uso sai de loading com recado e sem nova tentativa`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Error(AiCoachErrorKind.RATE_LIMITED, "quota")
            }
        )
        val viewModel = viewModel(gateway)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as GenerateWorkoutStatus.Message
        assertTrue(status.text.contains("limite de uso"))
        assertFalse(status.canRetry)
        assertEquals(1, gateway.generationCallCount)
    }

    @Test
    fun `timeout sai de loading e permite nova tentativa explicita`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            generationResponder = { AiWorkoutGenerationGatewayResult.Error(AiCoachErrorKind.TIMEOUT) }
        )
        val viewModel = viewModel(gateway)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as GenerateWorkoutStatus.Message
        assertTrue(status.canRetry)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(1, gateway.generationCallCount)
    }

    @Test
    fun `sem internet a mensagem diz que o resto do app continua funcionando`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            generationResponder = { AiWorkoutGenerationGatewayResult.Error(AiCoachErrorKind.NETWORK) }
        )
        val viewModel = viewModel(gateway)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as GenerateWorkoutStatus.Message
        assertTrue(status.text.contains("manualmente"))
        assertTrue(status.isWarning)
    }

    @Test
    fun `resposta invalida vira recado sem retry automatico`() = runTest(dispatcher) {
        val gateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Success(
                    WorkoutGenerationTestData.response(
                        exercises = listOf(WorkoutGenerationTestData.exerciseResponse("INVENTADO", order = 1))
                    )
                )
            }
        )
        val viewModel = viewModel(gateway)
        viewModel.configureFocus()

        viewModel.generate()
        dispatcher.scheduler.advanceUntilIdle()

        val status = viewModel.uiState.value.status as GenerateWorkoutStatus.Message
        assertTrue(status.text.contains("Nenhum treino foi criado"))
        assertEquals(1, gateway.generationCallCount)
    }

    @Test
    fun `foco aceita no maximo o limite configurado`() = runTest(dispatcher) {
        val viewModel = viewModel(successGateway())

        WorkoutGenerationPreferences.SELECTABLE_FOCUS_GROUPS.forEach { viewModel.toggleFocus(it) }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            com.example.domain.ai.AiModelConfig.MAX_FOCUS_MUSCLE_GROUPS,
            viewModel.uiState.value.focusMuscleGroups.size
        )
    }

    @Test
    fun `academia completa e exclusiva das demais opcoes`() = runTest(dispatcher) {
        val viewModel = viewModel(successGateway())

        viewModel.toggleEquipment(EquipmentAvailability.DUMBBELL)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf(EquipmentAvailability.DUMBBELL), viewModel.uiState.value.availableEquipment)

        viewModel.toggleEquipment(EquipmentAvailability.FULL_GYM)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf(EquipmentAvailability.FULL_GYM), viewModel.uiState.value.availableEquipment)
    }

    @Test
    fun `exclusao reduz os candidatos enviados sem sumir da lista de escolha`() = runTest(dispatcher) {
        val viewModel = viewModel(successGateway())
        viewModel.configureFocus()

        viewModel.toggleExclusion("supino-reto-barra")

        assertEquals(2, viewModel.uiState.value.candidates.size)
        assertEquals(1, viewModel.uiState.value.candidateCount)
    }
}
