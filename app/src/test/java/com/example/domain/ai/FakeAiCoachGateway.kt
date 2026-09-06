package com.example.domain.ai

import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationRequest
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutAdaptationRequest
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AiWorkoutGenerationRequest

/**
 * Provider falso: toda a fronteira acima do gateway é testável sem internet, sem Firebase,
 * sem Gemini e sem chave de API.
 */
class FakeAiCoachGateway(
    private val generationResponder: suspend (AiWorkoutGenerationRequest) -> AiWorkoutGenerationGatewayResult = {
        AiWorkoutGenerationGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE, "sem responder de geração")
    },
    private val adaptationResponder: suspend (AiWorkoutAdaptationRequest) -> AiWorkoutAdaptationGatewayResult = {
        AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE, "sem responder de adaptação")
    },
    private val explanationResponder: suspend (AiCoachExplanationRequest) -> AiCoachExplanationGatewayResult = {
        AiCoachExplanationGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE, "sem responder de explicação")
    },
    // Último parâmetro de propósito: os testes de análise passam este responder como lambda final.
    private val responder: suspend (AiCoachRequest) -> AiCoachGatewayResult = {
        AiCoachGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE, "sem responder de análise")
    }
) : AiCoachGateway {

    val requests = mutableListOf<AiCoachRequest>()

    val generationRequests = mutableListOf<AiWorkoutGenerationRequest>()

    val callCount: Int get() = requests.size

    val generationCallCount: Int get() = generationRequests.size

    val adaptationRequests = mutableListOf<AiWorkoutAdaptationRequest>()

    val adaptationCallCount: Int get() = adaptationRequests.size

    val explanationRequests = mutableListOf<AiCoachExplanationRequest>()

    val explanationCallCount: Int get() = explanationRequests.size

    override suspend fun request(request: AiCoachRequest): AiCoachGatewayResult {
        requests += request
        return responder(request)
    }

    override suspend fun generateWorkout(
        request: AiWorkoutGenerationRequest
    ): AiWorkoutGenerationGatewayResult {
        generationRequests += request
        return generationResponder(request)
    }

    override suspend fun adaptWorkout(
        request: AiWorkoutAdaptationRequest
    ): AiWorkoutAdaptationGatewayResult {
        adaptationRequests += request
        return adaptationResponder(request)
    }

    override suspend fun explain(
        request: AiCoachExplanationRequest
    ): AiCoachExplanationGatewayResult {
        explanationRequests += request
        return explanationResponder(request)
    }
}
