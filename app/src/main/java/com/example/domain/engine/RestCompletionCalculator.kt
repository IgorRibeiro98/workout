package com.example.domain.engine

import com.example.data.datastore.RestCompletionBehavior

/**
 * O que a tela de descanso deve mostrar num instante — a única conta de "quanto falta"/"quanto
 * passou" (T19.9).
 *
 * Existe para que `remaining`/`overtime` não sejam recalculados à mão em cada lugar que observa o
 * descanso (a tela de foco e, futuramente, qualquer outro): os dois derivam sempre de
 * `targetTimeMs - nowMs`, nunca de um contador decrementado pela UI.
 */
data class RestTimerReading(
    /** Segundos até o fim do descanso, nunca negativo — `0` a partir do instante em que expira. */
    val remainingSeconds: Long,
    /** Segundos **além** do descanso planejado, nunca negativo — `0` enquanto ainda não expirou. */
    val overtimeSeconds: Long,
    /** `now >= targetTime`. */
    val isExpired: Boolean,
    /** Expirou **e** a preferência é continuar contando — só aí a tela mostra o negativo. */
    val isOvertime: Boolean
)

/**
 * Deriva o estado do descanso de timestamps reais — nunca de um contador mantido pela UI.
 *
 * `RestCompletionBehavior.AUTO_ADVANCE` nunca produz [RestTimerReading.isOvertime]: a regra é que
 * o avanço automático apaga o alvo assim que o descanso termina, então "continuar contando" não
 * tem sentido nesse modo. A UI ainda assim pode ler `overtimeSeconds` — sempre `0` até expirar,
 * como QUALQUER leitura antes da expiração.
 */
object RestCompletionCalculator {

    fun read(targetTimeMs: Long, nowMs: Long, behavior: RestCompletionBehavior): RestTimerReading {
        val remainingMs = targetTimeMs - nowMs
        val isExpired = remainingMs <= 0
        val overtimeSeconds = if (isExpired) (-remainingMs) / 1000 else 0L
        return RestTimerReading(
            remainingSeconds = (remainingMs / 1000).coerceAtLeast(0L),
            overtimeSeconds = overtimeSeconds,
            isExpired = isExpired,
            // `00:00` é a fronteira (T19.9 §6.3): só depois de um segundo cheio decorrido além do
            // alvo é que a leitura vira overtime. Sem isto o primeiro instante expirado mostraria
            // "-00:00", um valor que a UI nunca deve exibir.
            isOvertime = isExpired && behavior == RestCompletionBehavior.MANUAL_OVERTIME && overtimeSeconds > 0
        )
    }

    /** O descanso que acabou de expirar deve avançar sozinho? Só `AUTO_ADVANCE` diz sim. */
    fun shouldAutoAdvance(behavior: RestCompletionBehavior): Boolean =
        behavior == RestCompletionBehavior.AUTO_ADVANCE
}
