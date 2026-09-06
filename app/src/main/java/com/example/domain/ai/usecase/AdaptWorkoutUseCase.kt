package com.example.domain.ai.usecase

import com.example.domain.ai.AiCoachGateway
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.AiWorkoutAdaptationContextBuilder
import com.example.domain.ai.AiWorkoutAdaptationValidation
import com.example.domain.ai.model.AdaptWorkoutResult
import com.example.domain.ai.model.AiCoachCallMetadata
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutAdaptationRequest
import java.util.UUID

/**
 * O caminho completo de uma adaptação:
 *
 * ```
 * WorkoutTemplate + histórico -> AiWorkoutAdaptationContextBuilder -> AiWorkoutAdaptationRequest
 *   -> AiCoachGateway -> AiWorkoutAdaptationResponse -> AiCoachResponseValidator
 *   -> WorkoutAdaptationDraft
 * ```
 *
 * Uma chamada por invocação explícita: não há retry automático, não há uma análise prévia
 * disparando uma adaptação e não há segunda chamada de revisão. Falha volta como
 * [AdaptWorkoutResult.Failure] e quem decide tentar de novo é o usuário.
 *
 * O caso de uso não escreve nada. Ele produz uma proposta que ainda vai ser revisada.
 */
class AdaptWorkoutUseCase(
    private val contextBuilder: AiWorkoutAdaptationContextBuilder,
    private val gateway: AiCoachGateway,
    private val telemetry: AiCoachTelemetry = AiCoachTelemetry.NoOp,
    private val requestIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val elapsedMsProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend operator fun invoke(templateId: Long): AdaptWorkoutResult {
        val requestId = requestIdProvider()
        val startedAt = elapsedMsProvider()

        val source = try {
            contextBuilder.build(templateId)
        } catch (e: Exception) {
            return finish(
                requestId = requestId,
                startedAt = startedAt,
                result = AdaptWorkoutResult.Failure(
                    kind = AiCoachErrorKind.UNAVAILABLE,
                    detail = "Falha ao montar o contexto: ${e.message}"
                )
            )
        } ?: return finish(
            requestId = requestId,
            startedAt = startedAt,
            result = AdaptWorkoutResult.Failure(
                kind = AiCoachErrorKind.UNAVAILABLE,
                detail = "Treino não encontrado ou sem exercícios para adaptar."
            )
        )

        val request = AiWorkoutAdaptationRequest(
            requestId = requestId,
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            context = source.context
        )

        var metadata = AiCoachCallMetadata.Unknown
        val result = when (val gatewayResult = gateway.adaptWorkout(request)) {
            is AiWorkoutAdaptationGatewayResult.Error ->
                AdaptWorkoutResult.Failure(gatewayResult.kind, gatewayResult.detail)

            is AiWorkoutAdaptationGatewayResult.Success -> {
                // Modelo e versão de prompt vêm do servidor (T16.2), nunca de constante local.
                metadata = gatewayResult.metadata
                val validation = AiCoachResponseValidator.validateWorkoutAdaptation(
                    requestId = requestId,
                    templateId = source.templateId,
                    sourceRevision = source.revision,
                    context = source.context,
                    response = gatewayResult.response
                )
                when (validation) {
                    is AiWorkoutAdaptationValidation.Valid -> {
                        val draft = validation.draft
                        // "Não há o que mudar" é resposta legítima, não erro.
                        if (draft.changes.isEmpty()) {
                            AdaptWorkoutResult.NoChanges(draft.summary, draft.dataQuality)
                        } else {
                            AdaptWorkoutResult.Success(draft)
                        }
                    }

                    is AiWorkoutAdaptationValidation.Invalid -> AdaptWorkoutResult.Failure(
                        kind = AiCoachErrorKind.INVALID_RESPONSE,
                        detail = validation.reason
                    )
                }
            }
        }

        return finish(requestId, startedAt, result, metadata)
    }

    private fun finish(
        requestId: String,
        startedAt: Long,
        result: AdaptWorkoutResult,
        metadata: AiCoachCallMetadata = AiCoachCallMetadata.Unknown
    ): AdaptWorkoutResult {
        telemetry.onRequestFinished(
            requestId = requestId,
            type = AiCoachRequestType.ADAPT_WORKOUT,
            model = metadata.model,
            promptVersion = metadata.promptVersion,
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            durationMs = elapsedMsProvider() - startedAt,
            result = when (result) {
                is AdaptWorkoutResult.Success -> "SUCCESS"
                is AdaptWorkoutResult.NoChanges -> "NO_CHANGES"
                is AdaptWorkoutResult.Failure -> result.kind.name
            }
        )
        return result
    }
}
