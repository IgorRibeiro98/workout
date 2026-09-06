package com.example.domain.ai.usecase

import com.example.domain.ai.AiCoachGateway
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.AiGeneratedWorkoutValidation
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.AiWorkoutGenerationContextBuilder
import com.example.domain.ai.model.AiCoachCallMetadata
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AiWorkoutGenerationRequest
import com.example.domain.ai.model.GenerateWorkoutResult
import com.example.domain.ai.model.WorkoutGenerationPreferences
import java.util.UUID

/**
 * O caminho completo de uma geração:
 *
 * ```
 * preferências -> AiWorkoutGenerationContextBuilder -> candidatos -> AiWorkoutGenerationRequest
 *   -> AiCoachGateway -> AiGeneratedWorkoutResponse -> AiCoachResponseValidator
 *   -> GeneratedWorkoutDraft
 * ```
 *
 * Uma chamada por invocação explícita: não há retry automático, não há segunda chamada de
 * revisão e não há crítica por outro modelo. Falha volta como
 * [GenerateWorkoutResult.Failure] e quem decide tentar de novo é o usuário.
 *
 * Sem candidatos o provider **não é chamado**: o resultado é determinístico e a cota fica
 * intacta.
 *
 * O caso de uso não escreve nada. Ele produz uma proposta.
 */
class GenerateWorkoutUseCase(
    private val contextBuilder: AiWorkoutGenerationContextBuilder,
    private val gateway: AiCoachGateway,
    private val telemetry: AiCoachTelemetry = AiCoachTelemetry.NoOp,
    private val requestIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val elapsedMsProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend operator fun invoke(preferences: WorkoutGenerationPreferences): GenerateWorkoutResult {
        val requestId = requestIdProvider()
        val startedAt = elapsedMsProvider()

        if (!preferences.isComplete) {
            return finish(
                requestId = requestId,
                startedAt = startedAt,
                result = GenerateWorkoutResult.Failure(
                    kind = AiCoachErrorKind.UNAVAILABLE,
                    detail = "Preferências incompletas: informe foco e duração."
                )
            )
        }

        val context = try {
            contextBuilder.build(preferences)
        } catch (e: Exception) {
            return finish(
                requestId = requestId,
                startedAt = startedAt,
                result = GenerateWorkoutResult.Failure(
                    kind = AiCoachErrorKind.UNAVAILABLE,
                    detail = "Falha ao montar o contexto: ${e.message}"
                )
            )
        }

        if (context.candidateExercises.isEmpty()) {
            return finish(requestId, startedAt, GenerateWorkoutResult.InsufficientCandidates)
        }

        val request = AiWorkoutGenerationRequest(
            requestId = requestId,
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            context = context
        )

        var metadata = AiCoachCallMetadata.Unknown
        val result = when (val gatewayResult = gateway.generateWorkout(request)) {
            is AiWorkoutGenerationGatewayResult.Error ->
                GenerateWorkoutResult.Failure(gatewayResult.kind, gatewayResult.detail)

            is AiWorkoutGenerationGatewayResult.Success -> {
                // Modelo e versão de prompt vêm do servidor (T16.2), nunca de constante local.
                metadata = gatewayResult.metadata
                val validation = AiCoachResponseValidator.validateGeneratedWorkout(
                    requestId = requestId,
                    context = context,
                    response = gatewayResult.response
                )
                when (validation) {
                    is AiGeneratedWorkoutValidation.Valid ->
                        GenerateWorkoutResult.Success(validation.draft)

                    AiGeneratedWorkoutValidation.InsufficientCandidates ->
                        GenerateWorkoutResult.InsufficientCandidates

                    is AiGeneratedWorkoutValidation.Invalid -> GenerateWorkoutResult.Failure(
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
        result: GenerateWorkoutResult,
        metadata: AiCoachCallMetadata = AiCoachCallMetadata.Unknown
    ): GenerateWorkoutResult {
        telemetry.onRequestFinished(
            requestId = requestId,
            type = AiCoachRequestType.GENERATE_WORKOUT,
            model = metadata.model,
            promptVersion = metadata.promptVersion,
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            durationMs = elapsedMsProvider() - startedAt,
            result = when (result) {
                is GenerateWorkoutResult.Success -> "SUCCESS"
                GenerateWorkoutResult.InsufficientCandidates -> "INSUFFICIENT_CANDIDATES"
                is GenerateWorkoutResult.Failure -> result.kind.name
            }
        )
        return result
    }
}
