package com.example.domain.ai.eval

import com.example.domain.ai.AiCoachTestData
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.CoachExplanationTestData
import com.example.domain.ai.FakeAiCoachGateway
import com.example.domain.ai.WorkoutAdaptationTestData
import com.example.domain.ai.WorkoutGenerationTestData
import com.example.domain.ai.eval.AiCoachEvaluationAssertions.assertAuthorizedExerciseIds
import com.example.domain.ai.eval.AiCoachEvaluationAssertions.assertCallCount
import com.example.domain.ai.eval.AiCoachEvaluationAssertions.assertContextOmits
import com.example.domain.ai.eval.AiCoachEvaluationAssertions.assertFiniteInRange
import com.example.domain.ai.eval.AiCoachEvaluationAssertions.assertKnownExerciseIds
import com.example.domain.ai.eval.AiCoachEvaluationAssertions.assertPresent
import com.example.domain.ai.eval.AiCoachEvaluationDimension.CONTEXT
import com.example.domain.ai.eval.AiCoachEvaluationDimension.COST
import com.example.domain.ai.eval.AiCoachEvaluationDimension.GROUNDING
import com.example.domain.ai.eval.AiCoachEvaluationDimension.LOCAL_FIRST
import com.example.domain.ai.eval.AiCoachEvaluationDimension.PRIVACY
import com.example.domain.ai.eval.AiCoachEvaluationDimension.RESILIENCE
import com.example.domain.ai.eval.AiCoachEvaluationDimension.SAFETY
import com.example.domain.ai.eval.AiCoachEvaluationDimension.SCHEMA
import com.example.domain.ai.eval.AiCoachEvaluationDimension.SEMANTIC
import com.example.domain.ai.eval.AiCoachEvaluationDimension.SIDE_EFFECTS
import com.example.domain.ai.eval.AiCoachEvaluationDimension.VERSIONING
import com.example.domain.ai.model.AdaptWorkoutResult
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationResult
import com.example.domain.ai.model.AiCoachExplanationSource
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiCoachResponseRecommendation
import com.example.domain.ai.model.AiCoachResult
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiExerciseExecutionContext
import com.example.domain.ai.model.AiExerciseHistoryContext
import com.example.domain.ai.model.AiExerciseLoadEvidenceContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.GenerateWorkoutResult
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import com.example.domain.ai.usecase.AdaptWorkoutUseCase
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
import com.example.domain.ai.usecase.ExplainCoachDecisionUseCase
import com.example.domain.ai.usecase.GenerateWorkoutUseCase
import kotlinx.serialization.json.Json

/**
 * Os cenários determinísticos da suíte de avaliação do Coach.
 *
 * Cada cenário roda um caminho real do Spark — caso de uso de verdade, contexto de verdade,
 * validador de verdade — com o provider substituído pelo [FakeAiCoachGateway]. A suíte roda
 * **offline**: sem Firebase, sem chave de API e sem consumir cota.
 *
 * Nenhuma asserção compara texto literal do modelo. O que se verifica são propriedades:
 * identificador conhecido, identificador autorizado, faixa numérica, campo obrigatório presente,
 * contagem de chamadas, limite de contexto e ausência de efeito colateral.
 *
 * O conjunto é pequeno e representativo de propósito: ele existe para detectar regressão quando
 * prompt, schema, context builder, validador ou configuração de modelo mudarem.
 */
object AiCoachEvaluationScenarios {

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    // -------------------------------------------------------------------------------------
    // Contextos base
    // -------------------------------------------------------------------------------------

    /** Treino com evolução clara de carga: 50 kg, 52,5 kg e 55 kg em execuções concluídas. */
    private fun analysisContext(
        sessionsAnalyzed: Int = 4,
        maxDataQuality: AiDataQualityLevel = AiDataQualityLevel.GOOD
    ) = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = sessionsAnalyzed),
        currentWorkout = AiWorkoutContext(
            templateName = "Peito + Tríceps",
            exercises = listOf(
                AiPlannedExerciseContext(
                    exerciseId = "supino-reto-barra",
                    name = "Supino reto com barra",
                    targetSets = 4,
                    minReps = 8,
                    maxReps = 12,
                    plannedWeightKg = 55f,
                    restSeconds = 90
                )
            )
        ),
        exerciseHistory = listOf(
            AiExerciseHistoryContext(
                exerciseId = "supino-reto-barra",
                name = "Supino reto com barra",
                sessionsAnalyzed = 3,
                executions = listOf(55f, 52.5f, 50f).map { weight ->
                    AiExerciseExecutionContext(
                        finishedAtEpochMs = null,
                        completedSets = 4,
                        maxWeightKg = weight,
                        totalReps = 40
                    )
                }
            )
        ),
        evidence = AiEvidenceContext(
            sessionsAnalyzed = sessionsAnalyzed,
            exercisesWithHistory = 1,
            maxDataQuality = maxDataQuality
        )
    )

    /** Treino montado, nenhuma sessão concluída: o app não tem o que sustentar. */
    private fun emptyHistoryContext() = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 3, completedSessionsInWindow = 0),
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
            sessionsAnalyzed = 0,
            exercisesWithHistory = 0,
            maxDataQuality = AiDataQualityLevel.INSUFFICIENT
        )
    )

    private fun analyze(gateway: FakeAiCoachGateway, context: AiCoachContext = analysisContext()) =
        AnalyzeWorkoutUseCase(EvaluationAnalysisContextBuilder(context), gateway)

    /** Pedido do cenário de geração: hipertrofia, peito e tríceps, academia. */
    private fun generationContext(
        candidates: List<AiCandidateExerciseContext> = listOf(
            WorkoutGenerationTestData.candidate("supino-reto-barra", "Supino reto com barra"),
            WorkoutGenerationTestData.candidate("crucifixo-halteres", "Crucifixo com halteres"),
            WorkoutGenerationTestData.candidate("triceps-corda", "Tríceps na corda")
        ),
        loadEvidence: List<AiExerciseLoadEvidenceContext> = listOf(
            AiExerciseLoadEvidenceContext(
                exerciseId = "supino-reto-barra",
                lastWeightKg = 60f,
                lastReps = 10,
                sessionsWithHistory = 3
            )
        ),
        notes: String? = null
    ) = WorkoutGenerationTestData.context(candidates = candidates, loadEvidence = loadEvidence)
        .copy(notes = notes)

    private fun generate(
        gateway: FakeAiCoachGateway,
        context: AiWorkoutGenerationContext = generationContext()
    ) = GenerateWorkoutUseCase(EvaluationGenerationContextBuilder(context), gateway)

    private fun adapt(
        gateway: FakeAiCoachGateway,
        context: AiWorkoutAdaptationContext? = WorkoutAdaptationTestData.context()
    ) = AdaptWorkoutUseCase(
        EvaluationAdaptationContextBuilder(
            templateId = WorkoutAdaptationTestData.TEMPLATE_ID,
            revision = WorkoutAdaptationTestData.REVISION,
            context = context
        ),
        gateway
    )

    /** Um gateway que devolve a proposta padrão, para cenários que precisam de um rascunho. */
    private fun adaptationDraftGateway() = FakeAiCoachGateway(
        adaptationResponder = {
            AiWorkoutAdaptationGatewayResult.Success(WorkoutAdaptationTestData.response())
        }
    )

    // -------------------------------------------------------------------------------------
    // Cenários
    // -------------------------------------------------------------------------------------

    val SCENARIOS: List<AiCoachEvaluationScenario> = listOf(

        // ---------------------------------------------------------------- ANALYZE_WORKOUT
        AiCoachEvaluationScenario(
            id = "analyze/evolucao-clara",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(GROUNDING, SEMANTIC, VERSIONING)
        ) {
            val context = analysisContext()
            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Success(
                    AiCoachTestData.response(
                        summary = "A carga subiu nas últimas execuções registradas.",
                        positiveSignals = listOf(
                            AiCoachTestData.observation(
                                exerciseId = "supino-reto-barra",
                                title = "Carga em evolução",
                                description = "50 kg, 52,5 kg e 55 kg nas execuções registradas."
                            )
                        ),
                        recommendations = listOf(
                            AiCoachTestData.recommendation(
                                type = AiRecommendationType.KEEP_CURRENT_PLAN.name,
                                exerciseId = "supino-reto-barra",
                                reason = "A progressão vem acontecendo com a estrutura atual.",
                                confidence = 0.8,
                                evidence = "50 kg, 52,5 kg e 55 kg nas execuções registradas"
                            )
                        ),
                        dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.GOOD)
                    )
                )
            }

            val advice = (analyze(gateway, context)() as AiCoachResult.Success).advice

            assertCallCount(1, gateway.callCount, "analyze")
            assertKnownExerciseIds(
                advice.recommendations.map { it.exerciseId } +
                    advice.positiveSignals.map { it.exerciseId },
                context.knownExerciseIds,
                "análise"
            )
            advice.recommendations.forEach {
                assertFiniteInRange(it.confidence, 0.0, 1.0, "confidence")
                assertPresent(it.reason, "reason")
                assertPresent(it.evidence, "evidence")
            }
            assertEquals(
                AiModelConfig.SCHEMA_VERSION,
                gateway.requests.single().schemaVersion,
                "a requisição precisa carregar a versão de schema configurada"
            )
        },

        AiCoachEvaluationScenario(
            id = "analyze/id-inexistente",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(GROUNDING, SEMANTIC)
        ) {
            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Success(
                    AiCoachTestData.response(
                        summary = "Análise.",
                        recommendations = listOf(
                            AiCoachTestData.recommendation(exerciseId = "exercicio-que-nao-existe")
                        )
                    )
                )
            }

            val result = analyze(gateway)() as AiCoachResult.Failure

            assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
            assertCallCount(1, gateway.callCount, "analyze")
        },

        AiCoachEvaluationScenario(
            id = "analyze/sem-historico-nao-inventa-progresso",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(GROUNDING, SEMANTIC, CONTEXT)
        ) {
            val context = emptyHistoryContext()

            // Conclusão forte sem evidência: recusada.
            val overconfident = FakeAiCoachGateway {
                AiCoachGatewayResult.Success(
                    AiCoachTestData.response(
                        summary = "Você está em um platô claro.",
                        dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.GOOD)
                    )
                )
            }
            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (analyze(overconfident, context)() as AiCoachResult.Failure).kind
            )

            // "Dados insuficientes" é resposta legítima.
            val honest = FakeAiCoachGateway {
                AiCoachGatewayResult.Success(
                    AiCoachTestData.response(
                        summary = "Ainda não há sessões concluídas para analisar.",
                        dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.INSUFFICIENT)
                    )
                )
            }
            val advice = (analyze(honest, context)() as AiCoachResult.Success).advice
            assertEquals(AiDataQualityLevel.INSUFFICIENT, advice.dataQuality.level)
            assertEquals(0, advice.sessionsAnalyzed)
            assertTrue(
                advice.recommendations.isEmpty(),
                "sem histórico não pode aparecer recomendação inventada"
            )
        },

        AiCoachEvaluationScenario(
            id = "analyze/resposta-fora-do-contrato",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(SCHEMA, SEMANTIC)
        ) {
            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Success(
                    AiCoachTestData.response(
                        summary = "Análise.",
                        recommendations = listOf(AiCoachTestData.recommendation(confidence = 4.2)),
                        dataQuality = null
                    )
                )
            }

            val result = analyze(gateway)() as AiCoachResult.Failure
            assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
        },

        AiCoachEvaluationScenario(
            id = "analyze/limite-de-uso",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(RESILIENCE, COST)
        ) {
            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Error(AiCoachErrorKind.RATE_LIMITED, "cota")
            }

            val result = analyze(gateway)() as AiCoachResult.Failure

            assertEquals(AiCoachErrorKind.RATE_LIMITED, result.kind)
            assertCallCount(1, gateway.callCount, "analyze com limite de uso")
        },

        AiCoachEvaluationScenario(
            id = "analyze/timeout",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(RESILIENCE, COST)
        ) {
            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Error(AiCoachErrorKind.TIMEOUT, "sem resposta")
            }

            val result = analyze(gateway)() as AiCoachResult.Failure

            assertEquals(AiCoachErrorKind.TIMEOUT, result.kind)
            assertCallCount(1, gateway.callCount, "analyze com timeout")
        },

        AiCoachEvaluationScenario(
            id = "analyze/offline",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(RESILIENCE, LOCAL_FIRST)
        ) {
            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Error(AiCoachErrorKind.NETWORK, "sem internet")
            }

            val result = analyze(gateway)() as AiCoachResult.Failure

            assertEquals(AiCoachErrorKind.NETWORK, result.kind)
            assertCallCount(1, gateway.callCount, "analyze offline")
        },

        AiCoachEvaluationScenario(
            id = "analyze/contexto-limitado-e-relevante",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(CONTEXT, PRIVACY)
        ) {
            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Success(AiCoachTestData.response(summary = "Análise."))
            }

            analyze(gateway)()

            val context = gateway.requests.single().context
            context.exerciseHistory.forEach {
                assertTrue(
                    it.executions.size <= AiModelConfig.HISTORY_PER_EXERCISE_LIMIT,
                    "histórico por exercício acima do teto: ${it.executions.size}"
                )
            }
            assertTrue(
                (context.currentWorkout?.exercises?.size ?: 0) <=
                    AiModelConfig.MAX_EXERCISES_IN_CONTEXT,
                "mais exercícios no contexto que o teto"
            )
            assertContextOmits(
                json.encodeToString(context),
                AiCoachEvaluationAssertions.GAMIFICATION_FIELDS,
                "contexto de análise"
            )
        },

        AiCoachEvaluationScenario(
            id = "analyze/sem-papel-clinico",
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            dimensions = setOf(SAFETY, SEMANTIC)
        ) {
            // O contrato de saída não tem lugar para conduta clínica: os tipos permitidos são um
            // conjunto fechado de revisões de treino.
            val clinical = AiRecommendationType.entries.filter { type ->
                listOf("DIAGNOS", "TREAT", "REHAB", "MEDIC").any { type.name.contains(it) }
            }
            assertTrue(clinical.isEmpty(), "há tipo de recomendação com papel clínico: $clinical")

            val gateway = FakeAiCoachGateway {
                AiCoachGatewayResult.Success(
                    AiCoachTestData.response(
                        summary = "Análise.",
                        recommendations = listOf(
                            AiCoachResponseRecommendation(
                                type = "PRESCREVER_FISIOTERAPIA",
                                exerciseId = "supino-reto-barra",
                                reason = "Dor relatada no ombro.",
                                confidence = 0.9,
                                evidence = "relato"
                            )
                        )
                    )
                )
            }

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (analyze(gateway)() as AiCoachResult.Failure).kind
            )
        },

        // --------------------------------------------------------------- GENERATE_WORKOUT
        AiCoachEvaluationScenario(
            id = "generate/hipertrofia-peito-triceps",
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            dimensions = setOf(GROUNDING, SCHEMA, SIDE_EFFECTS)
        ) {
            val context = generationContext()
            val gateway = FakeAiCoachGateway(
                generationResponder = {
                    AiWorkoutGenerationGatewayResult.Success(
                        WorkoutGenerationTestData.response(
                            exercises = listOf(
                                WorkoutGenerationTestData.exerciseResponse(
                                    "supino-reto-barra",
                                    order = 1,
                                    weightKg = 60.0
                                ),
                                WorkoutGenerationTestData.exerciseResponse("triceps-corda", order = 2)
                            )
                        )
                    )
                }
            )

            val result = generate(gateway, context)(WorkoutGenerationTestData.preferences())
            val draft = (result as GenerateWorkoutResult.Success).draft

            assertAuthorizedExerciseIds(
                draft.exercises.map { it.exerciseId },
                context.allowedExerciseIds,
                "geração"
            )
            draft.exercises.forEach { exercise ->
                assertFiniteInRange(exercise.sets.toDouble(), 1.0, 10.0, "sets")
                assertTrue(exercise.minReps <= exercise.maxReps, "faixa de repetições invertida")
                assertTrue(exercise.restSeconds > 0, "descanso inválido")
                exercise.weightKg?.let {
                    assertTrue(
                        exercise.exerciseId in context.exerciseIdsWithLoadEvidence,
                        "carga proposta sem carga registrada: ${exercise.exerciseId}"
                    )
                }
            }
            // O rascunho não é treino: o caso de uso não recebe nada que escreva.
            assertTrue(
                GenerateWorkoutUseCase::class.java.declaredFields.none { field ->
                    field.type.name.contains("Repository") || field.type.name.contains("Dao")
                },
                "o caso de uso de geração ganhou uma dependência que escreve"
            )
        },

        AiCoachEvaluationScenario(
            id = "generate/id-inexistente",
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            dimensions = setOf(GROUNDING, SIDE_EFFECTS)
        ) {
            val gateway = FakeAiCoachGateway(
                generationResponder = {
                    AiWorkoutGenerationGatewayResult.Success(
                        WorkoutGenerationTestData.response(
                            exercises = listOf(
                                WorkoutGenerationTestData.exerciseResponse(
                                    "exercicio-inventado",
                                    order = 1
                                )
                            )
                        )
                    )
                }
            )

            val result = generate(gateway)(WorkoutGenerationTestData.preferences())

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as GenerateWorkoutResult.Failure).kind
            )
        },

        AiCoachEvaluationScenario(
            id = "generate/id-nao-autorizado",
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            dimensions = setOf(GROUNDING, SEMANTIC)
        ) {
            // Id plausível do catálogo que **não** foi oferecido nesta requisição.
            val gateway = FakeAiCoachGateway(
                generationResponder = {
                    AiWorkoutGenerationGatewayResult.Success(
                        WorkoutGenerationTestData.response(
                            exercises = listOf(
                                WorkoutGenerationTestData.exerciseResponse(
                                    "supino-reto-barra",
                                    order = 1
                                ),
                                WorkoutGenerationTestData.exerciseResponse(
                                    "agachamento-livre",
                                    order = 2
                                )
                            )
                        )
                    )
                }
            )

            val result = generate(gateway)(WorkoutGenerationTestData.preferences())

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as GenerateWorkoutResult.Failure).kind
            )
        },

        AiCoachEvaluationScenario(
            id = "generate/sem-candidatos-nao-chama-provider",
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            dimensions = setOf(COST, LOCAL_FIRST)
        ) {
            val gateway = FakeAiCoachGateway()
            val context = generationContext(candidates = emptyList(), loadEvidence = emptyList())

            val result = generate(gateway, context)(WorkoutGenerationTestData.preferences())

            assertEquals(GenerateWorkoutResult.InsufficientCandidates, result)
            assertCallCount(0, gateway.generationCallCount, "generate sem candidatos")
        },

        AiCoachEvaluationScenario(
            id = "generate/carga-inventada",
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            dimensions = setOf(GROUNDING, SEMANTIC)
        ) {
            val gateway = FakeAiCoachGateway(
                generationResponder = {
                    AiWorkoutGenerationGatewayResult.Success(
                        WorkoutGenerationTestData.response(
                            exercises = listOf(
                                WorkoutGenerationTestData.exerciseResponse(
                                    "crucifixo-halteres",
                                    order = 1,
                                    weightKg = 30.0
                                )
                            )
                        )
                    )
                }
            )

            val result = generate(gateway)(WorkoutGenerationTestData.preferences())

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as GenerateWorkoutResult.Failure).kind
            )
        },

        AiCoachEvaluationScenario(
            id = "generate/contexto-relevante-e-limitado",
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            dimensions = setOf(CONTEXT, PRIVACY)
        ) {
            val gateway = FakeAiCoachGateway(
                generationResponder = {
                    AiWorkoutGenerationGatewayResult.Success(
                        WorkoutGenerationTestData.response(
                            exercises = listOf(
                                WorkoutGenerationTestData.exerciseResponse(
                                    "supino-reto-barra",
                                    order = 1
                                )
                            )
                        )
                    )
                }
            )

            generate(gateway)(WorkoutGenerationTestData.preferences())

            val context = gateway.generationRequests.single().context
            assertTrue(
                context.candidateExercises.size <= AiModelConfig.MAX_CANDIDATE_EXERCISES,
                "mais candidatos que o teto: ${context.candidateExercises.size}"
            )
            assertTrue(
                context.focusMuscleGroups.size <= AiModelConfig.MAX_FOCUS_MUSCLE_GROUPS,
                "mais grupos de foco que o teto"
            )
            assertContextOmits(
                json.encodeToString(context),
                AiCoachEvaluationAssertions.GAMIFICATION_FIELDS + listOf("personalRecord", "bodyWeight"),
                "contexto de geração"
            )
        },

        AiCoachEvaluationScenario(
            id = "generate/modelo-admite-falta-de-candidatos",
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            dimensions = setOf(SEMANTIC, SIDE_EFFECTS)
        ) {
            val gateway = FakeAiCoachGateway(
                generationResponder = {
                    AiWorkoutGenerationGatewayResult.Success(
                        WorkoutGenerationTestData.response(
                            exercises = emptyList(),
                            insufficientCandidates = true
                        )
                    )
                }
            )

            val result = generate(gateway)(WorkoutGenerationTestData.preferences())

            assertEquals(GenerateWorkoutResult.InsufficientCandidates, result)
        },

        // ------------------------------------------------------------------ ADAPT_WORKOUT
        AiCoachEvaluationScenario(
            id = "adapt/60-para-62-5",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(GROUNDING, SCHEMA, SIDE_EFFECTS)
        ) {
            val context = WorkoutAdaptationTestData.context()
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Success(WorkoutAdaptationTestData.response())
                }
            )

            val result = adapt(gateway, context)(WorkoutAdaptationTestData.TEMPLATE_ID)
            val draft = (result as AdaptWorkoutResult.Success).draft
            val change = draft.changes.single()

            assertKnownExerciseIds(
                listOf(change.exerciseId),
                context.templateExerciseIds,
                "adaptação"
            )
            assertPresent(change.reason, "reason")
            assertPresent(change.evidence, "evidence")
            // O antes vem do treino como ele está hoje; o depois é a proposta.
            assertEquals(
                WorkoutAdaptationValue.Load(60f),
                change.currentValue,
                "currentValue precisa repetir o treino atual"
            )
            assertEquals(
                WorkoutAdaptationValue.Load(62.5f),
                change.suggestedValue,
                "suggestedValue precisa ser a proposta validada"
            )
            assertTrue(
                change.currentValue != change.suggestedValue,
                "valor sugerido igual ao atual"
            )
            assertFiniteInRange(change.confidence, 0.0, 1.0, "confidence")
            assertEquals(
                WorkoutAdaptationTestData.REVISION,
                draft.sourceRevision,
                "a proposta precisa carregar a revisão do treino que o modelo enxergou"
            )
        },

        AiCoachEvaluationScenario(
            id = "adapt/id-inexistente",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(GROUNDING)
        ) {
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Success(
                        WorkoutAdaptationTestData.response(
                            changes = listOf(
                                WorkoutAdaptationTestData.loadChange(exerciseId = "nao-existe")
                            )
                        )
                    )
                }
            )

            val result = adapt(gateway)(WorkoutAdaptationTestData.TEMPLATE_ID)

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as AdaptWorkoutResult.Failure).kind
            )
        },

        AiCoachEvaluationScenario(
            id = "adapt/substituto-nao-autorizado",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(GROUNDING, SEMANTIC)
        ) {
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Success(
                        WorkoutAdaptationTestData.response(
                            changes = listOf(
                                WorkoutAdaptationTestData.replacementChange(
                                    replacementExerciseId = "crucifixo-maquina"
                                )
                            )
                        )
                    )
                }
            )

            val result = adapt(gateway)(WorkoutAdaptationTestData.TEMPLATE_ID)

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as AdaptWorkoutResult.Failure).kind
            )
        },

        AiCoachEvaluationScenario(
            id = "adapt/valor-atual-divergente",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(SEMANTIC, GROUNDING)
        ) {
            // O treino tem 60 kg; o modelo declara 80 kg — raciocinou sobre outro treino.
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Success(
                        WorkoutAdaptationTestData.response(
                            changes = listOf(
                                WorkoutAdaptationTestData.loadChange(
                                    currentWeightKg = 80.0,
                                    suggestedWeightKg = 82.5
                                )
                            )
                        )
                    )
                }
            )

            val result = adapt(gateway)(WorkoutAdaptationTestData.TEMPLATE_ID)

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as AdaptWorkoutResult.Failure).kind
            )
        },

        AiCoachEvaluationScenario(
            id = "adapt/tipo-nao-autorizado",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(SEMANTIC, CONTEXT)
        ) {
            // Com pouca evidência o app não oferece progressão de carga nesta requisição.
            val context = WorkoutAdaptationTestData.context(
                allowedChangeTypes = listOf(WorkoutAdaptationType.ADJUST_REST),
                maxDataQuality = AiDataQualityLevel.LIMITED,
                sessionsAnalyzed = 1
            )
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Success(
                        WorkoutAdaptationTestData.response(
                            dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.LIMITED)
                        )
                    )
                }
            )

            val result = adapt(gateway, context)(WorkoutAdaptationTestData.TEMPLATE_ID)

            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as AdaptWorkoutResult.Failure).kind
            )
        },

        AiCoachEvaluationScenario(
            id = "adapt/nao-mudar-nada-e-legitimo",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(SEMANTIC, SAFETY)
        ) {
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Success(
                        WorkoutAdaptationTestData.response(
                            summary = "Os dados ainda não sustentam nenhuma mudança.",
                            changes = emptyList()
                        )
                    )
                }
            )

            val result = adapt(gateway)(WorkoutAdaptationTestData.TEMPLATE_ID)

            assertTrue(
                result is AdaptWorkoutResult.NoChanges,
                "não propor mudança precisa ser estado próprio, não erro"
            )
        },

        AiCoachEvaluationScenario(
            id = "adapt/provider-indisponivel",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(RESILIENCE, LOCAL_FIRST)
        ) {
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE, null)
                }
            )

            val result = adapt(gateway)(WorkoutAdaptationTestData.TEMPLATE_ID)

            assertEquals(
                AiCoachErrorKind.UNAVAILABLE,
                (result as AdaptWorkoutResult.Failure).kind
            )
            assertCallCount(1, gateway.adaptationCallCount, "adapt indisponível")
        },

        AiCoachEvaluationScenario(
            id = "adapt/contexto-limitado-e-relevante",
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            dimensions = setOf(CONTEXT, PRIVACY)
        ) {
            val gateway = FakeAiCoachGateway(
                adaptationResponder = {
                    AiWorkoutAdaptationGatewayResult.Success(WorkoutAdaptationTestData.response())
                }
            )

            adapt(gateway)(WorkoutAdaptationTestData.TEMPLATE_ID)

            val context = gateway.adaptationRequests.single().context
            context.exerciseHistory.forEach {
                assertTrue(
                    it.executions.size <= AiModelConfig.HISTORY_PER_EXERCISE_LIMIT,
                    "histórico por exercício acima do teto"
                )
            }
            assertTrue(
                context.replacementCandidates.size <= AiModelConfig.MAX_CANDIDATE_EXERCISES,
                "mais substitutos que o teto"
            )
            assertContextOmits(
                json.encodeToString(context),
                AiCoachEvaluationAssertions.GAMIFICATION_FIELDS,
                "contexto de adaptação"
            )
        },

        // ---------------------------------------------------------------------- EXPLAIN_*
        AiCoachEvaluationScenario(
            id = "explain/recomendacao-e-local",
            requestType = AiCoachRequestType.EXPLAIN_RECOMMENDATION,
            dimensions = setOf(COST, LOCAL_FIRST)
        ) {
            val gateway = FakeAiCoachGateway()
            val useCase = ExplainCoachDecisionUseCase(gateway)
            val advice = CoachExplanationTestData.advice()

            val result = useCase.explainAnalysisTarget(
                advice,
                advice.recommendations.single().id
            ) as AiCoachExplanationResult.Success

            // Razão e evidência já existem: explicar não paga chamada.
            assertCallCount(0, gateway.explanationCallCount, "explicar recomendação")
            assertEquals(AiCoachExplanationSource.LOCAL, result.explanation.source)
            assertTrue(
                result.explanation.evidenceItems.isNotEmpty(),
                "explicação local sem bloco de evidência"
            )
        },

        AiCoachEvaluationScenario(
            id = "explain/treino-gerado",
            requestType = AiCoachRequestType.EXPLAIN_WORKOUT,
            dimensions = setOf(GROUNDING, SCHEMA)
        ) {
            val gateway = FakeAiCoachGateway(
                explanationResponder = {
                    AiCoachExplanationGatewayResult.Success(
                        CoachExplanationTestData.explanationResponse(
                            referencedExerciseIds = listOf("supino-reto-barra")
                        )
                    )
                }
            )
            val useCase = ExplainCoachDecisionUseCase(gateway)

            val result = useCase.explainGeneratedWorkout(
                CoachExplanationTestData.generatedDraft(),
                CoachExplanationTestData.preferences()
            ) as AiCoachExplanationResult.Success

            assertCallCount(1, gateway.explanationCallCount, "explicar treino")
            val context = gateway.explanationRequests.single().context
            assertKnownExerciseIds(
                listOf("supino-reto-barra"),
                context.knownExerciseIds,
                "explicação"
            )
            assertEquals(AiCoachExplanationSource.MODEL, result.explanation.source)
            assertEquals(
                AiModelConfig.SCHEMA_VERSION,
                gateway.explanationRequests.single().schemaVersion
            )
        },

        AiCoachEvaluationScenario(
            id = "explain/id-inexistente-cai-para-local",
            requestType = AiCoachRequestType.EXPLAIN_WORKOUT,
            dimensions = setOf(GROUNDING, RESILIENCE)
        ) {
            val gateway = FakeAiCoachGateway(
                explanationResponder = {
                    AiCoachExplanationGatewayResult.Success(
                        CoachExplanationTestData.explanationResponse(
                            referencedExerciseIds = listOf("exercicio-que-nao-existe")
                        )
                    )
                }
            )
            val useCase = ExplainCoachDecisionUseCase(gateway)

            val result = useCase.explainGeneratedWorkout(
                CoachExplanationTestData.generatedDraft(),
                CoachExplanationTestData.preferences()
            ) as AiCoachExplanationResult.Success

            // O texto do modelo foi recusado: o usuário recebe a explicação local do app.
            assertEquals(AiCoachExplanationSource.LOCAL, result.explanation.source)
            assertTrue(
                result.explanation.limitations.isNotEmpty(),
                "o fallback precisa dizer por que a explicação está mais curta"
            )
        },

        AiCoachEvaluationScenario(
            id = "explain/adaptacao-com-provider-fora",
            requestType = AiCoachRequestType.EXPLAIN_ADAPTATION,
            dimensions = setOf(RESILIENCE, LOCAL_FIRST)
        ) {
            val context = WorkoutAdaptationTestData.context()
            val draftGateway = adaptationDraftGateway()
            val draft = (adapt(draftGateway, context)(WorkoutAdaptationTestData.TEMPLATE_ID)
                as AdaptWorkoutResult.Success).draft

            val explainGateway = FakeAiCoachGateway(
                explanationResponder = {
                    AiCoachExplanationGatewayResult.Error(AiCoachErrorKind.NETWORK, "sem internet")
                }
            )
            val useCase = ExplainCoachDecisionUseCase(
                gateway = explainGateway,
                adaptationContextBuilder = EvaluationAdaptationContextBuilder(
                    templateId = WorkoutAdaptationTestData.TEMPLATE_ID,
                    revision = WorkoutAdaptationTestData.REVISION,
                    context = context
                )
            )

            val result = useCase.explainAdaptationChange(draft, draft.changes.single().id)
                as AiCoachExplanationResult.Success

            assertEquals(AiCoachExplanationSource.LOCAL, result.explanation.source)
            assertTrue(
                result.explanation.evidenceItems.isNotEmpty(),
                "o fallback precisa mostrar a evidência que o app já tinha"
            )
            assertTrue(result.explanation.limitations.isNotEmpty(), "fallback sem limitação")
        },

        AiCoachEvaluationScenario(
            id = "explain/adaptacao-obsoleta-nao-chama-provider",
            requestType = AiCoachRequestType.EXPLAIN_ADAPTATION,
            dimensions = setOf(COST, SEMANTIC)
        ) {
            val context = WorkoutAdaptationTestData.context()
            val draftGateway = adaptationDraftGateway()
            val draft = (adapt(draftGateway, context)(WorkoutAdaptationTestData.TEMPLATE_ID)
                as AdaptWorkoutResult.Success).draft

            val explainGateway = FakeAiCoachGateway()
            val useCase = ExplainCoachDecisionUseCase(
                gateway = explainGateway,
                // O treino mudou depois da proposta: outra revisão.
                adaptationContextBuilder = EvaluationAdaptationContextBuilder(
                    templateId = WorkoutAdaptationTestData.TEMPLATE_ID,
                    revision = "revisao-diferente",
                    context = context
                )
            )

            val result = useCase.explainAdaptationChange(draft, draft.changes.single().id)

            assertEquals(AiCoachExplanationResult.StaleContext, result)
            assertCallCount(0, explainGateway.explanationCallCount, "explicar proposta obsoleta")
        },

        AiCoachEvaluationScenario(
            id = "explain/progresso-nao-recalcula",
            requestType = AiCoachRequestType.EXPLAIN_PROGRESS,
            dimensions = setOf(GROUNDING, SIDE_EFFECTS)
        ) {
            val gateway = FakeAiCoachGateway(
                explanationResponder = {
                    AiCoachExplanationGatewayResult.Success(
                        // Progresso não fala de exercício: qualquer id aqui é invenção.
                        CoachExplanationTestData.explanationResponse(
                            referencedExerciseIds = listOf("supino-reto-barra")
                        )
                    )
                }
            )
            val useCase = ExplainCoachDecisionUseCase(gateway)
            val snapshot = CoachExplanationTestData.progress()

            val result = useCase.explainProgress(snapshot) as AiCoachExplanationResult.Success

            assertEquals(AiCoachExplanationSource.LOCAL, result.explanation.source)
            val context = gateway.explanationRequests.single().context
            assertTrue(
                context.knownExerciseIds.isEmpty(),
                "contexto de progresso não deve autorizar nenhum exercício"
            )
            assertTrue(
                context.facts.any { it.value.contains(snapshot.level.toString()) },
                "os números do app precisam chegar prontos ao contexto"
            )
        },

        AiCoachEvaluationScenario(
            id = "explain/cache-evita-segunda-chamada",
            requestType = AiCoachRequestType.EXPLAIN_WORKOUT,
            dimensions = setOf(COST)
        ) {
            val gateway = FakeAiCoachGateway(
                explanationResponder = {
                    AiCoachExplanationGatewayResult.Success(
                        CoachExplanationTestData.explanationResponse()
                    )
                }
            )
            val useCase = ExplainCoachDecisionUseCase(gateway)
            val draft = CoachExplanationTestData.generatedDraft()
            val preferences = CoachExplanationTestData.preferences()

            useCase.explainGeneratedWorkout(draft, preferences)
            useCase.explainGeneratedWorkout(draft, preferences)

            assertCallCount(1, gateway.explanationCallCount, "explicar o mesmo alvo duas vezes")
        }
    )
}
