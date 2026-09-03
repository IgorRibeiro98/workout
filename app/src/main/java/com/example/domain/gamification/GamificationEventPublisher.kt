package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent

/**
 * Ponto de saída de fatos do aplicativo.
 *
 * O motor de treino conhece apenas esta interface: ele informa "algo aconteceu" e segue seu fluxo.
 * Quem interpreta o fato (persistência, XP, conquistas) vive do outro lado da fronteira.
 */
interface GamificationEventPublisher {

    /**
     * Registra o fato.
     *
     * @return `true` quando o evento passou a existir no histórico, `false` quando ele já havia sido
     *         registrado antes (mesma `dedupeKey`) ou quando não há consumidor configurado.
     */
    suspend fun publish(event: GamificationEvent): Boolean

    /** Implementação neutra usada quando a gamificação não está conectada (testes, previews). */
    object NoOp : GamificationEventPublisher {
        override suspend fun publish(event: GamificationEvent): Boolean = false
    }
}
