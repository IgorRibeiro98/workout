package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventMetadata
import com.example.domain.gamification.model.GamificationEventType

data class XpReward(
    val amount: Int,
    val reason: String
)

object XpRewardPolicy {
    /**
     * Versão da política de recompensa.
     *
     * Ela só avança quando o valor de um fato **já registrado** muda, porque é isso que obriga a
     * reconstruir o histórico de XP. Passar a reconhecer um tipo de fato novo (como a conclusão de
     * missão) não altera nenhuma recompensa antiga e, portanto, não pede reconstrução.
     */
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
            GamificationEventType.MISSION_COMPLETED -> missionReward(event)
        }
    }

    /**
     * A recompensa da missão vem gravada no próprio fato.
     *
     * Ler do evento (e não do catálogo atual) mantém o XP histórico determinístico: se a recompensa
     * de uma missão mudar amanhã, o que o usuário já recebeu continua valendo exatamente o que
     * valia no dia da conclusão.
     */
    private fun missionReward(event: GamificationEvent): XpReward? {
        val amount = event.metadata[GamificationEventMetadata.MISSION_REWARD_XP]?.toIntOrNull() ?: 0
        if (amount <= 0) return null
        return XpReward(amount, "Missão Concluída")
    }
}
