package com.example.domain.ai.usecase

import com.example.domain.ai.AiCoachContextBuilder
import com.example.domain.ai.AiCoachGateway
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.AiCoachValidation
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiCoachResult
import java.util.UUID

/**
 * O caminho completo de uma análise:
 *
 * ```
 * autoridades do domínio -> AiCoachContextBuilder -> AiCoachRequest -> AiCoachGateway
 *   -> AiCoachResponse -> AiCoachResponseValidator -> AiCoachAdvice
 * ```
 *
 * Uma chamada por invocação explícita: não há retry automático aqui. Falha recuperável volta
 * como [AiCoachResult.Failure] e quem decide tentar de novo é o usuário.
 *
 * O caso de uso não escreve nada. Ele lê autoridades e devolve sugestão.
 */
class AnalyzeWorkoutUseCase(
    private val contextBuilder: AiCoachContextBuilder,
    private val gateway: AiCoachGateway,
    private val telemetry: AiCoachTelemetry = AiCoachTelemetry.NoOp,
    private val requestIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val elapsedMsProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend operator fun invoke(): AiCoachResult {
        val requestId = requestIdProvider()
        val startedAt = elapsedMsProvider()

        val request = try {
            AiCoachRequest(
                requestId = requestId,
                schemaVersion = AiModelConfig.SCHEMA_VERSION,
                type = AiCoachRequestType.ANALYZE_WORKOUT,
                context = contextBuilder.build()
            )
        } catch (e: Exception) {
            return finish(
                requestId = requestId,
                startedAt = startedAt,
                result = AiCoachResult.Failure(
                    kind = AiCoachErrorKind.UNAVAILABLE,
                    detail = "Falha ao montar o contexto: ${e.message}"
                )
            )
        }

        val result = when (val gatewayResult = gateway.request(request)) {
            is AiCoachGatewayResult.Error ->
                AiCoachResult.Failure(gatewayResult.kind, gatewayResult.detail)

            is AiCoachGatewayResult.Success -> {
                val validation = AiCoachResponseValidator.validate(
                    requestId = requestId,
                    context = request.context,
                    response = gatewayResult.response
                )
                when (validation) {
                    is AiCoachValidation.Valid -> AiCoachResult.Success(validation.advice)
                    is AiCoachValidation.Invalid -> AiCoachResult.Failure(
                        kind = AiCoachErrorKind.INVALID_RESPONSE,
                        detail = validation.reason
                    )
                }
            }
        }

        return finish(requestId, startedAt, result)
    }

    private fun finish(requestId: String, startedAt: Long, result: AiCoachResult): AiCoachResult {
        telemetry.onRequestFinished(
            requestId = requestId,
            type = AiCoachRequestType.ANALYZE_WORKOUT,
            model = AiModelConfig.MODEL_NAME,
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            durationMs = elapsedMsProvider() - startedAt,
            result = when (result) {
                is AiCoachResult.Success -> "SUCCESS"
                is AiCoachResult.Failure -> result.kind.name
            }
        )
        return result
    }
}
