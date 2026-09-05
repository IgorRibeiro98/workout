package com.example.domain.ai

import com.example.domain.ai.model.AiDataQualityLevel

/**
 * Quanto o Coach pode afirmar, dado o que o app conseguiu enviar.
 *
 * Isto **não** é um motor de progressão: aqui só se conta quantas sessões concluídas entraram no
 * contexto. Nenhuma leitura do tipo "subiu 2,5 kg = progresso" existe no Spark — interpretar é
 * papel do Coach.
 *
 * O teto vai no contexto e é repetido no prompt, e o validador rejeita resposta que declare
 * evidência acima dele: afirmar histórico que não recebeu é a mesma classe de invenção que citar
 * um exercício inexistente.
 */
object AiDataQualityPolicy {

    /** Abaixo disto não há série histórica: uma ou duas execuções não mostram tendência. */
    const val SESSIONS_FOR_GOOD: Int = 3

    fun ceilingFor(sessionsAnalyzed: Int): AiDataQualityLevel = when {
        sessionsAnalyzed <= 0 -> AiDataQualityLevel.INSUFFICIENT
        sessionsAnalyzed < SESSIONS_FOR_GOOD -> AiDataQualityLevel.LIMITED
        else -> AiDataQualityLevel.GOOD
    }
}
