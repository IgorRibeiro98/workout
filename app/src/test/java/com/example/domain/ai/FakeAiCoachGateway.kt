package com.example.domain.ai

import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest

/**
 * Provider falso: toda a fronteira acima do gateway é testável sem internet, sem Firebase,
 * sem Gemini e sem chave de API.
 */
class FakeAiCoachGateway(
    private val responder: suspend (AiCoachRequest) -> AiCoachGatewayResult
) : AiCoachGateway {

    val requests = mutableListOf<AiCoachRequest>()

    val callCount: Int get() = requests.size

    override suspend fun request(request: AiCoachRequest): AiCoachGatewayResult {
        requests += request
        return responder(request)
    }
}
