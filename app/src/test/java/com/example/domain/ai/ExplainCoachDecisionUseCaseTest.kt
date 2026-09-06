package com.example.domain.ai

import com.example.domain.ai.CoachExplanationTestData.advice
import com.example.domain.ai.CoachExplanationTestData.explanationResponse
import com.example.domain.ai.CoachExplanationTestData.generatedDraft
import com.example.domain.ai.CoachExplanationTestData.history
import com.example.domain.ai.CoachExplanationTestData.preferences
import com.example.domain.ai.CoachExplanationTestData.progress
import com.example.domain.ai.CoachExplanationTestData.recommendation
import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachContextOrigin
import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationResult
import com.example.domain.ai.model.AiCoachExplanationSource
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import com.example.domain.ai.usecase.ExplainCoachDecisionUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A política de custo da T14.4, verificada sem provider real.
 *
 * As duas metades da regra:
 *
 * - o que o app já sabe responder **não** vira chamada;
 * - o que precisa de síntese vira **uma** chamada, sobre o contexto mínimo, e cai para a
 *   explicação local quando o provider falha.
 */
class ExplainCoachDecisionUseCaseTest {

    private val templateId = 7L
    private val revision = "rev-1"

    private fun loadChange(
        exerciseId: String = "supino-reto-barra",
        id: String = WorkoutAdaptationChange.idOf(WorkoutAdaptationType.ADJUST_LOAD, exerciseId)
    ) = WorkoutAdaptationChange(
        id = id,
        type = WorkoutAdaptationType.ADJUST_LOAD,
        exerciseId = exerciseId,
        exerciseName = "Supino reto com barra",
        currentValue = WorkoutAdaptationValue.Load(60f),
        suggestedValue = WorkoutAdaptationValue.Load(62.5f),
        reason = "O alvo foi atingido com a mesma carga.",
        evidence = "60 kg em 3 execuções concluídas",
        confidence = 0.84
    )

    private fun adaptationDraft(
        changes: List<WorkoutAdaptationChange> = listOf(loadChange()),
        sourceRevision: String = revision,
        level: AiDataQualityLevel = AiDataQualityLevel.GOOD
    ) = WorkoutAdaptationDraft(
        requestId = "adaptation-1",
        templateId = templateId,
        templateName = "Peito + Tríceps",
        sourceRevision = sourceRevision,
        summary = "Uma revisão de carga.",
        dataQuality = AiCoachDataQuality(level, "Baseada nas execuções concluídas."),
        changes = changes
    )

    /** O builder de adaptação, controlado: revisão e histórico que o teste quiser. */
    private fun adaptationBuilder(
        revisionOverride: String = revision,
        exercises: List<com.example.domain.ai.model.AiPlannedExerciseContext> =
            listOf(WorkoutAdaptationTestData.plannedExercise()),
        historyList: List<com.example.domain.ai.model.AiExerciseHistoryContext> = listOf(history()),
        source: Boolean = true
    ) = object : AiWorkoutAdaptationContextBuilder {
        override suspend fun build(templateId: Long): AiWorkoutAdaptationSource? {
            if (!source) return null
            return AiWorkoutAdaptationSource(
                templateId = templateId,
                revision = revisionOverride,
                context = WorkoutAdaptationTestData.context(exercises = exercises)
                    .copy(exerciseHistory = historyList)
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // 1. Explicação local de recomendação: reason/evidence já respondem
    // -------------------------------------------------------------------------------------

    @Test
    fun `explicar recomendacao com evidencia nao chama o provider`() = runTest {
        val gateway = FakeAiCoachGateway()
        val advice = advice()

        val result = ExplainCoachDecisionUseCase(gateway)
            .explainAnalysisTarget(advice, "${AiCoachAdvice.RECOMMENDATION_ID_PREFIX}:0")

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertEquals(0, gateway.explanationCallCount)
        assertEquals(AiCoachExplanationSource.LOCAL, explanation.source)
        assertEquals(AiCoachContextOrigin.WORKOUT_ANALYSIS, explanation.origin)
        // O texto sai do que já estava validado, não de uma nova redação.
        assertEquals("A carga permaneceu igual nas últimas sessões.", explanation.explanation)
        assertTrue(
            "a evidência da recomendação precisa aparecer",
            explanation.evidenceItems.any { it.contains("60 kg nas últimas 4 sessões concluídas") }
        )
        assertTrue(
            "o app conta as sessões, não o modelo",
            explanation.evidenceItems.any { it.contains("Sessões concluídas analisadas: 4") }
        )
    }

    @Test
    fun `explicar ponto de atencao tambem e local`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway)
            .explainAnalysisTarget(advice(), "${AiCoachAdvice.ATTENTION_POINT_ID_PREFIX}:0")

        assertTrue(result is AiCoachExplanationResult.Success)
        assertEquals(0, gateway.explanationCallCount)
    }

    // -------------------------------------------------------------------------------------
    // 3. Alvo inexistente
    // -------------------------------------------------------------------------------------

    @Test
    fun `recomendacao inexistente nao chama o provider`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway)
            .explainAnalysisTarget(advice(), "REC:99")

        assertEquals(AiCoachExplanationResult.TargetNotFound, result)
        assertEquals(0, gateway.explanationCallCount)
    }

    // -------------------------------------------------------------------------------------
    // 2/4/5. Adaptação: uma chamada, alvo inexistente, contexto obsoleto
    // -------------------------------------------------------------------------------------

    @Test
    fun `explicar adaptacao usa exatamente uma chamada e o contexto minimo`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(
                    explanationResponse(referencedExerciseIds = listOf("supino-reto-barra"))
                )
            }
        )

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder())
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertEquals("uma ação = uma chamada", 1, gateway.explanationCallCount)
        assertEquals(AiCoachExplanationSource.MODEL, explanation.source)

        val request = gateway.explanationRequests.single()
        assertEquals(AiCoachRequestType.EXPLAIN_ADAPTATION, request.type)
        assertEquals(loadChange().id, request.context.contextId)
        assertEquals("supino-reto-barra", request.context.exerciseId)
        // Só o exercício em foco: nada do resto do treino nem do catálogo.
        assertEquals(setOf("supino-reto-barra"), request.context.knownExerciseIds)
        assertEquals("60 kg", request.context.currentValue)
        assertEquals("62.5 kg", request.context.suggestedValue)
        assertNotNull(request.context.exerciseHistory)

        // A evidência exibida é fato do app, montada do contexto — não texto do modelo.
        assertEquals(
            request.context.facts.map { it.line },
            explanation.evidenceItems
        )
        assertTrue(
            explanation.evidenceItems.any { it.contains("Execuções concluídas consideradas: 3") }
        )
    }

    @Test
    fun `mudanca inexistente na proposta nao chama o provider`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder())
            .explainAdaptationChange(adaptationDraft(), "ADJUST_LOAD:exercicio-que-nao-existe")

        assertEquals(AiCoachExplanationResult.TargetNotFound, result)
        assertEquals(0, gateway.explanationCallCount)
    }

    @Test
    fun `proposta descartada nao pode ser explicada`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder())
            .explainAdaptationChange(adaptationDraft(changes = emptyList()), loadChange().id)

        assertEquals(AiCoachExplanationResult.TargetNotFound, result)
        assertEquals(0, gateway.explanationCallCount)
    }

    @Test
    fun `treino alterado depois da proposta produz contexto obsoleto sem chamar o provider`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder(revisionOverride = "rev-2"))
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        assertEquals(AiCoachExplanationResult.StaleContext, result)
        assertEquals("explicar estado obsoleto seria descrever o que não existe", 0, gateway.explanationCallCount)
    }

    @Test
    fun `treino apagado depois da proposta nao chama o provider`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder(source = false))
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        assertEquals(AiCoachExplanationResult.TargetNotFound, result)
        assertEquals(0, gateway.explanationCallCount)
    }

    // -------------------------------------------------------------------------------------
    // 6/7. IDs inventados e evidência
    // -------------------------------------------------------------------------------------

    @Test
    fun `resposta que cita exerciseId fora do contexto cai para a explicacao local`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(
                    explanationResponse(referencedExerciseIds = listOf("exercicio-inventado"))
                )
            }
        )

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder())
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        // O texto inventado é descartado inteiro; o usuário recebe o que o app sustenta.
        assertEquals(AiCoachExplanationSource.LOCAL, explanation.source)
        assertFalse(explanation.explanation.contains("exercicio-inventado"))
    }

    @Test
    fun `evidencia nunca vem do modelo`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(
                    explanationResponse(explanation = "Você treinou 40 vezes com 200 kg.")
                )
            }
        )

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder())
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertTrue(
            "nenhuma evidência pode conter número que o app não enviou",
            explanation.evidenceItems.none { it.contains("200 kg") }
        )
    }

    // -------------------------------------------------------------------------------------
    // 8. Limitações
    // -------------------------------------------------------------------------------------

    @Test
    fun `pouca evidencia produz limitacao visivel`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway).explainAnalysisTarget(
            advice(level = AiDataQualityLevel.LIMITED, sessionsAnalyzed = 2),
            "${AiCoachAdvice.RECOMMENDATION_ID_PREFIX}:0"
        )

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertTrue(
            explanation.limitations.any { it.contains("apenas 2 sessões registradas") }
        )
    }

    @Test
    fun `limitacao reconhecida pelo app sobrevive a resposta do modelo`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(explanationResponse(limitations = emptyList()))
            }
        )

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder(historyList = emptyList()))
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertEquals(AiCoachExplanationSource.MODEL, explanation.source)
        assertTrue(
            "o modelo não pode fazer sumir uma limitação que o app reconheceu",
            explanation.limitations.any { it.contains("Não há execução concluída registrada") }
        )
    }

    // -------------------------------------------------------------------------------------
    // 15/16/21/22. Fallback local, offline, rate limit e timeout
    // -------------------------------------------------------------------------------------

    @Test
    fun `provider indisponivel devolve explicacao local em vez de erro`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE)
            }
        )

        val result = ExplainCoachDecisionUseCase(gateway, adaptationBuilder())
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertEquals(AiCoachExplanationSource.LOCAL, explanation.source)
        assertTrue(explanation.explanation.contains("O alvo foi atingido com a mesma carga."))
        assertTrue(explanation.evidenceItems.isNotEmpty())
        assertTrue(explanation.limitations.any { it.contains("não está disponível agora") })
    }

    @Test
    fun `sem internet a explicacao local continua funcionando`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = { AiCoachExplanationGatewayResult.Error(AiCoachErrorKind.NETWORK) }
        )

        val result = ExplainCoachDecisionUseCase(gateway).explainProgress(progress())

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertEquals(AiCoachExplanationSource.LOCAL, explanation.source)
        assertTrue(explanation.limitations.any { it.contains("Sem internet") })
    }

    @Test
    fun `limite de uso e tempo esgotado saem do loading com explicacao local`() = runTest {
        listOf(AiCoachErrorKind.RATE_LIMITED, AiCoachErrorKind.TIMEOUT).forEach { kind ->
            val gateway = FakeAiCoachGateway(
                explanationResponder = { AiCoachExplanationGatewayResult.Error(kind) }
            )

            val result = ExplainCoachDecisionUseCase(gateway)
                .explainGeneratedWorkout(generatedDraft(), preferences())

            val explanation = (result as AiCoachExplanationResult.Success).explanation
            assertEquals(AiCoachExplanationSource.LOCAL, explanation.source)
            assertEquals(1, gateway.explanationCallCount)
            assertTrue("sem retry automático", explanation.limitations.isNotEmpty())
        }
    }

    // -------------------------------------------------------------------------------------
    // 19/20. Cache em memória e invalidação
    // -------------------------------------------------------------------------------------

    @Test
    fun `mesmo contexto nao chama o provider duas vezes`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = { AiCoachExplanationGatewayResult.Success(explanationResponse()) }
        )
        val useCase = ExplainCoachDecisionUseCase(gateway)
        val draft = generatedDraft()

        useCase.explainGeneratedWorkout(draft, preferences())
        useCase.explainGeneratedWorkout(draft, preferences())

        assertEquals(1, gateway.explanationCallCount)
    }

    @Test
    fun `contexto alterado invalida o cache`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = { AiCoachExplanationGatewayResult.Success(explanationResponse()) }
        )
        val useCase = ExplainCoachDecisionUseCase(gateway)
        val draft = generatedDraft(
            exercises = listOf(
                CoachExplanationTestData.draftExercise(),
                CoachExplanationTestData.draftExercise("crucifixo-halteres", "Crucifixo", 1)
            )
        )

        useCase.explainGeneratedWorkout(draft, preferences())
        // O usuário removeu um exercício da proposta: a explicação anterior descrevia outra lista.
        useCase.explainGeneratedWorkout(
            draft.copy(exercises = draft.exercises.take(1)),
            preferences()
        )

        assertEquals(2, gateway.explanationCallCount)
    }

    @Test
    fun `falha do provider nao entra no cache`() = runTest {
        var attempts = 0
        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                attempts++
                AiCoachExplanationGatewayResult.Error(AiCoachErrorKind.NETWORK)
            }
        )
        val useCase = ExplainCoachDecisionUseCase(gateway)

        useCase.explainProgress(progress())
        useCase.explainProgress(progress())

        // Tentar de novo depois de uma falha continua sendo possível — o fallback não vira cache.
        assertEquals(2, attempts)
    }

    // -------------------------------------------------------------------------------------
    // 23/25. Treino gerado e progresso
    // -------------------------------------------------------------------------------------

    @Test
    fun `explicacao de treino gerado leva o pedido e os exercicios propostos`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = { AiCoachExplanationGatewayResult.Success(explanationResponse()) }
        )

        ExplainCoachDecisionUseCase(gateway).explainGeneratedWorkout(
            generatedDraft(),
            preferences(excluded = setOf("crucifixo-halteres"))
        )

        val context = gateway.explanationRequests.single().context
        assertEquals(AiCoachContextOrigin.GENERATED_WORKOUT.name, context.origin)
        assertEquals(setOf("supino-reto-barra"), context.knownExerciseIds)
        assertTrue(context.facts.any { it.label == "Objetivo pedido" })
        assertTrue(context.facts.any { it.label == "Exercícios excluídos por você" })
        // O treino ainda não foi executado: isso é limitação declarada, não silêncio.
        assertTrue(context.knownLimitations.any { it.contains("ainda não foi executado") })
    }

    @Test
    fun `explicacao de progresso usa somente numeros das autoridades`() = runTest {
        val gateway = FakeAiCoachGateway(
            explanationResponder = { AiCoachExplanationGatewayResult.Success(explanationResponse()) }
        )
        val snapshot = progress()

        val result = ExplainCoachDecisionUseCase(gateway).explainProgress(snapshot)

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        val context = gateway.explanationRequests.single().context
        assertEquals(AiCoachRequestType.EXPLAIN_PROGRESS, gateway.explanationRequests.single().type)
        assertEquals("5", context.facts.first { it.label == "Nível atual" }.value)
        assertEquals("4 semana(s)", context.facts.first { it.label == "Sequência semanal" }.value)
        // Nenhum exercício entra: progressão não é sobre exercício.
        assertTrue(context.knownExerciseIds.isEmpty())
        assertTrue(
            explanation.limitations.any { it.contains("sem recalcular nível") }
        )
    }

    @Test
    fun `proposta vazia nao vira explicacao`() = runTest {
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway)
            .explainGeneratedWorkout(generatedDraft(exercises = emptyList()), preferences())

        assertEquals(AiCoachExplanationResult.TargetNotFound, result)
        assertEquals(0, gateway.explanationCallCount)
    }
}
