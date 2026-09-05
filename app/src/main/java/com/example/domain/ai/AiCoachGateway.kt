package com.example.domain.ai

import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AiWorkoutGenerationRequest

/**
 * Fronteira entre o Spark e qualquer provider de IA.
 *
 * Acima desta interface ninguém conhece Firebase, Gemini, endpoint ou SDK. Trocar de provider
 * é trocar a implementação; o domínio e a apresentação não mudam.
 *
 * Um método por tipo de request, porque cada tipo tem contexto e schema próprios — e um gateway
 * só, porque timeout, tratamento de erro e configuração de modelo são os mesmos.
 */
interface AiCoachGateway {

    /** Análise do treino (T14.0/T14.1). */
    suspend fun request(request: AiCoachRequest): AiCoachGatewayResult

    /** Geração de uma proposta de treino (T14.2). O gateway não persiste nada. */
    suspend fun generateWorkout(request: AiWorkoutGenerationRequest): AiWorkoutGenerationGatewayResult
}
