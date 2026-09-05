package com.example.domain.ai

import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
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
    // Último parâmetro de propósito: os testes de análise passam este responder como lambda final.
    private val responder: suspend (AiCoachRequest) -> AiCoachGatewayResult = {
        AiCoachGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE, "sem responder de análise")
    }
) : AiCoachGateway {

    val requests = mutableListOf<AiCoachRequest>()

    val generationRequests = mutableListOf<AiWorkoutGenerationRequest>()

    val callCount: Int get() = requests.size

    val generationCallCount: Int get() = generationRequests.size

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
}
