package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType

data class XpReward(
    val amount: Int,
    val reason: String
)

object XpRewardPolicy {
    const val VERSION = 1

    fun rewardFor(event: GamificationEvent): XpReward? {
        return when (event.type) {
            GamificationEventType.WORKOUT_STARTED -> null
            GamificationEventType.WORKOUT_COMPLETED -> XpReward(100, "Treino Concluído")
            GamificationEventType.FIRST_WORKOUT_COMPLETED -> XpReward(100, "Primeiro Treino")
            GamificationEventType.EXERCISE_COMPLETED -> null
            GamificationEventType.FIRST_EXERCISE_COMPLETED -> null
            GamificationEventType.PERSONAL_RECORD_CREATED -> XpReward(50, "Novo Recorde Pessoal")
            GamificationEventType.WEEKLY_GOAL_COMPLETED -> XpReward(150, "Meta Semanal Atingida")
            GamificationEventType.STREAK_MILESTONE_REACHED -> null
        }
    }
}
