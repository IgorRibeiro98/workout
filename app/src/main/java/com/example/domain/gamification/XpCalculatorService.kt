package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.XpTransactionRepository

class XpCalculatorService(
    private val repository: XpTransactionRepository
) {
    suspend fun processEvent(event: GamificationEvent) {
        val reward = XpRewardPolicy.rewardFor(event)
        if (reward != null) {
            val hasTransaction = repository.hasTransactionForEvent(event.id)
            if (!hasTransaction) {
                repository.saveTransaction(
                    XpTransaction(
                        eventId = event.id,
                        amount = reward.amount,
                        reason = reward.reason,
                        createdAt = event.timestamp
                    )
                )
            }
        }
    }
}
