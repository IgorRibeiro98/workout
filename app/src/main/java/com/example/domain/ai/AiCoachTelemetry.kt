package com.example.domain.ai

import com.example.domain.ai.model.AiCoachRequestType

/**
 * Observabilidade da fronteira de IA: apenas metadata técnica.
 *
 * Prompt, contexto, histórico, medidas corporais e resposta do modelo não passam por aqui.
 */
interface AiCoachTelemetry {

    fun onRequestFinished(
        requestId: String,
        type: AiCoachRequestType,
        model: String,
        schemaVersion: Int,
        durationMs: Long,
        result: String
    )

    /** Padrão para testes e para qualquer chamador que não queira registrar nada. */
    object NoOp : AiCoachTelemetry {
        override fun onRequestFinished(
            requestId: String,
            type: AiCoachRequestType,
            model: String,
            schemaVersion: Int,
            durationMs: Long,
            result: String
        ) = Unit
    }
}
