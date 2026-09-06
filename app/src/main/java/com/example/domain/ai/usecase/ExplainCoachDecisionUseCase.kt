package com.example.domain.ai.usecase

import com.example.domain.ai.AiCoachExplanationCache
import com.example.domain.ai.AiCoachExplanationContextBuilder
import com.example.domain.ai.AiCoachExplanationPlan
import com.example.domain.ai.AiCoachExplanationValidation
import com.example.domain.ai.AiCoachGateway
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.AiWorkoutAdaptationContextBuilder
import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachExplanation
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationRequest
import com.example.domain.ai.model.AiCoachExplanationResult
import com.example.domain.ai.model.AiCoachExplanationSource
import com.example.domain.ai.model.AiProgressSnapshot
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutGenerationPreferences
import java.util.UUID

/**
 * O caminho completo de uma explicação contextual:
 *
 * ```
 * origem + contextId -> alvo resolvido no estado ATUAL
 *   -> AiCoachExplanationContextBuilder -> contexto mínimo + explicação local
 *   -> política: local basta?
 *        sim -> explicação local (0 chamadas)
 *        não -> cache em memória -> AiCoachGateway.explain -> AiCoachResponseValidator
 *   -> AiCoachExplanation -> UI
 * ```
 *
 * **READ-ONLY por construção.** Este caso de uso recebe apenas repositórios/builders de leitura;
 * ele não conhece `WorkoutRepository`, DAO de escrita, publicador de gamificação nem qualquer
 * caminho que persista. Não há como uma explicação alterar treino, sessão, XP, PR, conquista ou
 * missão — não porque se tomou cuidado, mas porque a dependência não existe.
 *
 * Uma chamada por invocação explícita: não há retry automático e não há segunda chamada de
 * revisão. Falha recuperável cai para a explicação local, que é sempre montada antes.
 */
class ExplainCoachDecisionUseCase(
    private val gateway: AiCoachGateway,
    /**
     * A mesma autoridade que a T14.3 usa para montar uma adaptação.
     *
     * Ela é relida no momento da explicação por dois motivos: trazer o histórico real do
     * exercício em foco e detectar que o treino mudou desde que a proposta foi gerada.
     */
    private val adaptationContextBuilder: AiWorkoutAdaptationContextBuilder? = null,
    private val telemetry: AiCoachTelemetry = AiCoachTelemetry.NoOp,
    private val cache: AiCoachExplanationCache = AiCoachExplanationCache(),
    private val requestIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val elapsedMsProvider: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * "Por que você recomendou isso?" sobre uma recomendação ou observação da análise.
     *
     * Resolvida contra a análise **atual**: um id que não existe mais devolve
     * [AiCoachExplanationResult.TargetNotFound] sem tocar no provider.
     */
    suspend fun explainAnalysisTarget(
        advice: AiCoachAdvice,
        targetId: String,
        exerciseNameResolver: suspend (String) -> String? = { null }
    ): AiCoachExplanationResult {
        val requestId = requestIdProvider()
        val target = advice.explainableTarget(targetId)
            ?: return AiCoachExplanationResult.TargetNotFound

        val exerciseId = when (target) {
            is com.example.domain.ai.model.AiExplainableTarget.Recommendation ->
                target.recommendation.exerciseId

            is com.example.domain.ai.model.AiExplainableTarget.Observation ->
                target.observation.exerciseId
        }

        val plan = AiCoachExplanationContextBuilder.forAnalysisTarget(
            requestId = requestId,
            advice = advice,
            targetId = targetId,
            target = target,
            exerciseName = exerciseId?.let { exerciseNameResolver(it) }
        )
        return resolve(requestId, plan)
    }

    /** "Por que esse treino foi montado assim?" sobre a proposta ainda não salva. */
    suspend fun explainGeneratedWorkout(
        draft: GeneratedWorkoutDraft,
        preferences: WorkoutGenerationPreferences
    ): AiCoachExplanationResult {
        val requestId = requestIdProvider()
        if (draft.exercises.isEmpty()) return AiCoachExplanationResult.TargetNotFound

        return resolve(
            requestId,
            AiCoachExplanationContextBuilder.forGeneratedWorkout(requestId, draft, preferences)
        )
    }

    /**
     * "Entender sugestão" sobre uma mudança proposta e ainda não aplicada.
     *
     * Duas guardas antes de qualquer chamada: a mudança precisa existir na proposta atual, e o
     * treino precisa estar na mesma revisão de quando a proposta foi montada. Explicar uma
     * adaptação já descartada, ou montada sobre um treino que mudou, seria descrever algo que não
     * existe mais.
     */
    suspend fun explainAdaptationChange(
        draft: WorkoutAdaptationDraft,
        changeId: String
    ): AiCoachExplanationResult {
        val requestId = requestIdProvider()
        val change = draft.change(changeId) ?: return AiCoachExplanationResult.TargetNotFound

        val source = try {
            adaptationContextBuilder?.build(draft.templateId)
        } catch (e: Exception) {
            return AiCoachExplanationResult.Failure(
                kind = com.example.domain.ai.model.AiCoachErrorKind.UNAVAILABLE,
                detail = "Falha ao reler o treino: ${e.message}"
            )
        }
        // Treino apagado, esvaziado ou editado depois da proposta: resultado controlado, sem IA.
        if (source == null) return AiCoachExplanationResult.TargetNotFound
        if (source.revision != draft.sourceRevision) return AiCoachExplanationResult.StaleContext
        if (source.context.plannedExercise(change.exerciseId) == null) {
            return AiCoachExplanationResult.TargetNotFound
        }

        val history = source.context.exerciseHistory.firstOrNull { it.exerciseId == change.exerciseId }

        return resolve(
            requestId,
            AiCoachExplanationContextBuilder.forAdaptationChange(requestId, draft, change, history)
        )
    }

    /** "Por que meu Coach diz que estou evoluindo?" sobre os números do Perfil. */
    suspend fun explainProgress(snapshot: AiProgressSnapshot): AiCoachExplanationResult {
        val requestId = requestIdProvider()
        return resolve(requestId, AiCoachExplanationContextBuilder.forProgress(requestId, snapshot))
    }

    // -------------------------------------------------------------------------------------

    /**
     * A política de custo, em um lugar só.
     *
     * Ordem: dados estruturados existentes -> explicação local -> Gemini somente se agregar
     * valor. E, dentro do caminho do Gemini: cache em memória antes da chamada.
     */
    private suspend fun resolve(
        requestId: String,
        plan: AiCoachExplanationPlan
    ): AiCoachExplanationResult {
        if (!plan.useModel) {
            // Sem telemetria de provider: nenhuma chamada foi feita.
            return AiCoachExplanationResult.Success(plan.local)
        }

        cache.get(plan.target)?.let { cached ->
            return AiCoachExplanationResult.Success(cached)
        }

        val startedAt = elapsedMsProvider()
        val request = AiCoachExplanationRequest(
            requestId = requestId,
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            type = plan.target.requestType,
            context = plan.context
        )

        val gatewayResult = gateway.explain(request)
        val result = when (gatewayResult) {
            is AiCoachExplanationGatewayResult.Error -> fallback(plan, gatewayResult.kind)

            is AiCoachExplanationGatewayResult.Success -> {
                when (
                    val validation = AiCoachResponseValidator.validateExplanation(
                        context = plan.context,
                        response = gatewayResult.response
                    )
                ) {
                    is AiCoachExplanationValidation.Valid -> {
                        val explanation = AiCoachExplanation(
                            requestId = requestId,
                            origin = plan.local.origin,
                            contextId = plan.local.contextId,
                            title = validation.title,
                            explanation = validation.explanation,
                            // Evidência é fato do app: o mesmo bloco com ou sem IA.
                            evidenceItems = plan.local.evidenceItems,
                            // As limitações que o app reconhece vêm primeiro e não podem sumir.
                            limitations = (plan.local.limitations + validation.limitations).distinct(),
                            source = AiCoachExplanationSource.MODEL
                        )
                        cache.put(plan.target, explanation)
                        AiCoachExplanationResult.Success(explanation)
                    }

                    is AiCoachExplanationValidation.Invalid -> fallback(
                        plan,
                        com.example.domain.ai.model.AiCoachErrorKind.INVALID_RESPONSE
                    )
                }
            }
        }

        telemetry.onRequestFinished(
            requestId = requestId,
            type = plan.target.requestType,
            model = AiModelConfig.MODEL_NAME,
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            durationMs = elapsedMsProvider() - startedAt,
            result = when (gatewayResult) {
                is AiCoachExplanationGatewayResult.Success ->
                    if ((result as? AiCoachExplanationResult.Success)
                            ?.explanation?.source == AiCoachExplanationSource.MODEL
                    ) {
                        "SUCCESS"
                    } else {
                        "INVALID_RESPONSE"
                    }

                is AiCoachExplanationGatewayResult.Error -> gatewayResult.kind.name
            }
        )
        return result
    }

    /**
     * O modelo não respondeu, mas o app já tinha o que dizer.
     *
     * O texto do fallback é o mesmo que seria mostrado offline: montado só com dados existentes,
     * nada inventado. A limitação acrescentada diz por que a explicação está mais curta — não
     * esconder isso é parte do contrato.
     */
    private fun fallback(
        plan: AiCoachExplanationPlan,
        kind: com.example.domain.ai.model.AiCoachErrorKind
    ): AiCoachExplanationResult = AiCoachExplanationResult.Success(
        plan.local.copy(
            limitations = plan.local.limitations + fallbackLimitation(kind)
        )
    )

    private fun fallbackLimitation(
        kind: com.example.domain.ai.model.AiCoachErrorKind
    ): String = when (kind) {
        com.example.domain.ai.model.AiCoachErrorKind.NETWORK ->
            "Sem internet agora: esta explicação foi montada com os dados que o app já tinha."

        com.example.domain.ai.model.AiCoachErrorKind.RATE_LIMITED ->
            "O Coach IA atingiu o limite de uso: esta explicação foi montada com os dados que o " +
                "app já tinha."

        com.example.domain.ai.model.AiCoachErrorKind.TIMEOUT ->
            "O Coach IA demorou demais para responder: esta explicação foi montada com os dados " +
                "que o app já tinha."

        else ->
            "O Coach IA não está disponível agora: esta explicação foi montada com os dados que " +
                "o app já tinha."
    }
}
