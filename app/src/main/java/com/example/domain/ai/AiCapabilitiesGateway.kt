package com.example.domain.ai

import com.example.domain.ai.model.AiCapabilitiesGatewayResult

/**
 * A fronteira do app com `GET /v1/account/capabilities` (T19.0).
 *
 * Existe pela mesma razão de [AiCoachGateway]: uma interface no domínio permite um dublê
 * determinístico nos testes de ViewModel, sem rede e sem depender da produção real
 * ([com.example.data.ai.SparkAiCapabilitiesGateway]).
 *
 * O resultado é **informativo**, nunca autoritativo: toda operação real do Coach continua sendo
 * validada de novo pelo backend, mesmo que esta consulta tenha dito "permitido".
 */
interface AiCapabilitiesGateway {
    suspend fun fetch(): AiCapabilitiesGatewayResult
}
