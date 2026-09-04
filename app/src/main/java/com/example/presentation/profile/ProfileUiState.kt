package com.example.presentation.profile

import com.example.domain.evolution.model.achievement.Achievement

/**
 * Projeção do progresso do atleta para a tela de Perfil.
 *
 * Todo valor aqui é *cópia* de uma fonte de verdade existente (XP, consistência, conquistas,
 * histórico de treinos, recordes, medidas corporais). Nenhuma regra de gamificação nasce nesta
 * classe: ela só reúne o que o domínio já calculou para que a Composable apenas renderize.
 */
data class ProfileUiState(
    val isLoading: Boolean = true,

    // Identidade de progressão — origem: XpTransactionRepository.getUserProgress()
    val level: Int = 1,
    val totalXp: Int = 0,
    val currentLevelXp: Int = 0,
    val xpForNextLevel: Int = 0,
    val levelProgress: Float = 0f,

    // Consistência — origem: ConsistencyRepository
    val streakWeeks: Int = 0,
    val weeklyCompleted: Int = 0,
    val weeklyGoal: Int = 0,
    /** Meta já agendada para a próxima semana; igual a [weeklyGoal] quando não há mudança pendente. */
    val nextWeekGoal: Int = 0,

    // Histórico — origem: WorkoutRepository (somente sessões COMPLETED)
    val completedWorkouts: Int = 0,

    // Conquistas — origem: AchievementRepository.getAchievementsFlow()
    val unlockedAchievements: Int = 0,
    val totalAchievements: Int = 0,
    val recentAchievements: List<Achievement> = emptyList(),

    // Recordes — origem: tabela persistida de personal records
    val personalRecordsCount: Int = 0,

    // Corpo — origem: BodyMeasurementRepository
    val latestWeightKg: Float? = null
) {
    /** Quanto falta para o próximo nível, derivado do [UserProgress] canônico. */
    val xpToNextLevel: Int
        get() = (xpForNextLevel - currentLevelXp).coerceAtLeast(0)

    /** A meta muda apenas na virada da semana; enquanto isso a semana atual segue com a antiga. */
    val hasPendingGoalChange: Boolean
        get() = nextWeekGoal != weeklyGoal
}
