package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.XpTransactionRepository

class XpCalculatorService(
    private val repository: XpTransactionRepository
) {
    suspend fun processEvent(event: GamificationEvent) {
        val amount = calculateXp(event.type)
        if (amount > 0) {
            val hasTransaction = repository.hasTransactionForEvent(event.id)
            if (!hasTransaction) {
                repository.saveTransaction(
                    XpTransaction(
                        eventId = event.id,
                        amount = amount,
                        reason = getReason(event.type),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun calculateXp(type: GamificationEventType): Int {
        return when (type) {
            GamificationEventType.WORKOUT_STARTED -> 10
            GamificationEventType.WORKOUT_COMPLETED -> 50
            GamificationEventType.EXERCISE_COMPLETED -> 5
            GamificationEventType.FIRST_EXERCISE_COMPLETED -> 15
            GamificationEventType.PERSONAL_RECORD_CREATED -> 30
            GamificationEventType.STREAK_MILESTONE_REACHED -> 100
            GamificationEventType.WEEKLY_GOAL_COMPLETED -> 150
            else -> 0
        }
    }

    private fun getReason(type: GamificationEventType): String {
        return when (type) {
            GamificationEventType.WORKOUT_STARTED -> "Treino Iniciado"
            GamificationEventType.WORKOUT_COMPLETED -> "Treino Concluído"
            GamificationEventType.EXERCISE_COMPLETED -> "Exercício Concluído"
            GamificationEventType.FIRST_EXERCISE_COMPLETED -> "Primeiro Exercício Concluído"
            GamificationEventType.PERSONAL_RECORD_CREATED -> "Novo Recorde Pessoal"
            GamificationEventType.STREAK_MILESTONE_REACHED -> "Marco de Consistência"
            GamificationEventType.WEEKLY_GOAL_COMPLETED -> "Meta Semanal Atingida"
            else -> "Recompensa"
        }
    }
}
