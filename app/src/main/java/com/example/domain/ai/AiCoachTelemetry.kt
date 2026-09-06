package com.example.domain.ai

import com.example.domain.ai.model.AiCoachRequestType

/**
 * Observabilidade da fronteira de IA: apenas metadata técnica.
 *
 * Prompt, contexto, histórico, medidas corporais e resposta do modelo não passam por aqui.
 *
 * O conjunto de campos é fechado de propósito — `requestId`, tipo, modelo, versão de prompt,
 * versão de schema, duração e classe do resultado. Com ele dá para responder "qual chamada
 * falhou, quanto demorou, com qual modelo/prompt/schema e que erro deu" sem que nenhum dado de
 * treino, corpo ou texto do usuário saia do aparelho.
 */
interface AiCoachTelemetry {

    fun onRequestFinished(
        requestId: String,
        type: AiCoachRequestType,
        model: String,
        promptVersion: Int,
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
            promptVersion: Int,
            schemaVersion: Int,
            durationMs: Long,
            result: String
        ) = Unit
    }
}
