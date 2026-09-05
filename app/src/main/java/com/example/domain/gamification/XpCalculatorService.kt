package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.XpTransactionOrigin
import com.example.domain.gamification.repository.XpTransactionRepository

/**
 * Traduz fatos em XP.
 *
 * [XpRewardPolicy] continua sendo a autoridade sobre quanto vale cada fato; aqui a recompensa vira
 * a transação persistida. Manter essa tradução em um único lugar garante que o ganho ao vivo e a
 * reconstrução histórica produzam exatamente a mesma transação.
 */
class XpCalculatorService(
    private val repository: XpTransactionRepository
) {
    /**
     * Converte o fato em XP.
     *
     * [origin] decide apenas a visibilidade: em [XpTransactionOrigin.LIVE] o ganho vira feedback
     * imediato; em [XpTransactionOrigin.RECONCILIATION] o mesmo XP é gravado em silêncio, porque
     * reconstruir o passado não é conquistar XP agora. Em qualquer origem a gravação é idempotente
     * por evento.
     */
    suspend fun processEvent(
        event: GamificationEvent,
        origin: XpTransactionOrigin = XpTransactionOrigin.LIVE
    ) {
        val transaction = transactionFor(event) ?: return
        val hasTransaction = repository.hasTransactionForEvent(event.id)
        if (!hasTransaction) {
            repository.saveTransaction(transaction, origin)
        }
    }

    /** Conversão pura do fato em transação, sem persistir nem notificar ninguém. */
    fun transactionFor(event: GamificationEvent): XpTransaction? {
        val reward = XpRewardPolicy.rewardFor(event) ?: return null
        return XpTransaction(
            eventId = event.id,
            amount = reward.amount,
            reason = reward.reason,
            createdAt = event.timestamp
        )
    }
}
