package com.example.domain.ai

import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest

/**
 * Fronteira entre o Spark e qualquer provider de IA.
 *
 * Acima desta interface ninguém conhece Firebase, Gemini, endpoint ou SDK. Trocar de provider
 * é trocar a implementação; o domínio e a apresentação não mudam.
 */
interface AiCoachGateway {
    suspend fun request(request: AiCoachRequest): AiCoachGatewayResult
}
