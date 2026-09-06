package com.example.data.ai

import android.util.Log
import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.model.AiCoachRequestType

/**
 * Registra apenas metadata técnica da chamada, em debug e em release.
 *
 * Prompt, contexto, histórico, medidas corporais e texto do modelo nunca entram no log — nem em
 * debug. O que fica registrado é o que permite correlacionar uma falha: id da requisição, tipo,
 * modelo, versões e classe do resultado.
 */
class LogcatAiCoachTelemetry : AiCoachTelemetry {

    override fun onRequestFinished(
        requestId: String,
        type: AiCoachRequestType,
        model: String,
        promptVersion: Int,
        schemaVersion: Int,
        durationMs: Long,
        result: String
    ) {
        Log.i(
            TAG,
            "requestId=$requestId type=$type model=$model promptVersion=$promptVersion " +
                "schemaVersion=$schemaVersion durationMs=$durationMs result=$result"
        )
    }

    private companion object {
        const val TAG = "AiCoach"
    }
}
