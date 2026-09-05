package com.example.data.ai

import android.util.Log
import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.model.AiCoachRequestType

/**
 * Registra apenas metadata técnica da chamada.
 *
 * Prompt, contexto, histórico, medidas corporais e texto do modelo nunca entram no log.
 */
class LogcatAiCoachTelemetry : AiCoachTelemetry {

    override fun onRequestFinished(
        requestId: String,
        type: AiCoachRequestType,
        model: String,
        schemaVersion: Int,
        durationMs: Long,
        result: String
    ) {
        Log.i(
            TAG,
            "requestId=$requestId type=$type model=$model schemaVersion=$schemaVersion " +
                "durationMs=$durationMs result=$result"
        )
    }

    private companion object {
        const val TAG = "AiCoach"
    }
}
