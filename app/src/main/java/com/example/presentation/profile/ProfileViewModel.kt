package com.example.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.repository.ConsistencyRepository
import com.example.domain.gamification.repository.XpTransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Reúne, sem recalcular, as autoridades de progressão que o Perfil do Atleta apresenta.
 *
 * Cada número exibido continua pertencendo ao seu dono original:
 *
 * ```
 * XpTransactionRepository    -> nível, XP total, progresso do nível
 * ConsistencyRepository      -> sequência semanal, meta e progresso da semana
 * AchievementRepository      -> conquistas desbloqueadas / total
 * WorkoutRepository          -> treinos concluídos, recordes pessoais
 * BodyMeasurementRepository  -> último peso registrado
 * ```
 *
 * O Perfil é uma projeção dessas fontes: nada aqui persiste contadores derivados nem reimplementa
 * curva de nível, streak ou desbloqueio de conquista.
 */
class ProfileViewModel(
    private val xpTransactionRepository: XpTransactionRepository,
    private val consistencyRepository: ConsistencyRepository,
    private val achievementRepository: com.example.domain.evolution.repository.AchievementRepository,
    private val workoutRepository: WorkoutRepository,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** Recorte do estado montado a partir das fontes de progressão. */
    private data class ProgressionSnapshot(
        val level: Int,
        val totalXp: Int,
        val currentLevelXp: Int,
        val xpForNextLevel: Int,
        val levelProgress: Float,
        val streakWeeks: Int,
        val weeklyCompleted: Int,
        val weeklyGoal: Int,
        val completedWorkouts: Int,
        val unlockedAchievements: Int,
        val totalAchievements: Int,
        val recentAchievements: List<Achievement>,
        val personalRecordsCount: Int
    )

    init {
        observeProfile()
    }

    private fun observeProfile() {
        viewModelScope.launch {
            val progressionFlow = combine(
                xpTransactionRepository.getUserProgress(),
                consistencyRepository.getConsistencyProgressFlow(),
                achievementRepository.getAchievementsFlow(),
                workoutRepository.getCompletedSessionsCountFlow(),
                workoutRepository.getPersonalRecordsCountFlow()
            ) { userProgress, consistency, achievements, completedWorkouts, personalRecords ->
                ProgressionSnapshot(
                    level = userProgress.currentLevel,
                    totalXp = userProgress.totalXp,
                    currentLevelXp = userProgress.currentLevelXp,
                    xpForNextLevel = userProgress.xpForNextLevel,
                    levelProgress = userProgress.progressPercentage,
                    streakWeeks = consistency.currentStreakWeeks,
                    weeklyCompleted = consistency.currentWeekCompleted,
                    weeklyGoal = consistency.currentWeekGoal,
                    completedWorkouts = completedWorkouts,
                    unlockedAchievements = achievements.count { it.unlockedAt != null },
                    totalAchievements = achievements.size,
                    recentAchievements = achievements.toRecentPreview(),
                    personalRecordsCount = personalRecords
                )
            }

            combine(
                progressionFlow,
                bodyMeasurementRepository.latestMeasurement,
                settingsManager.weeklyGoalFlow
            ) { progression, latestMeasurement, nextWeekGoal ->
                ProfileUiState(
                    isLoading = false,
                    level = progression.level,
                    totalXp = progression.totalXp,
                    currentLevelXp = progression.currentLevelXp,
                    xpForNextLevel = progression.xpForNextLevel,
                    levelProgress = progression.levelProgress,
                    streakWeeks = progression.streakWeeks,
                    weeklyCompleted = progression.weeklyCompleted,
                    weeklyGoal = progression.weeklyGoal,
                    nextWeekGoal = nextWeekGoal,
                    completedWorkouts = progression.completedWorkouts,
                    unlockedAchievements = progression.unlockedAchievements,
                    totalAchievements = progression.totalAchievements,
                    recentAchievements = progression.recentAchievements,
                    personalRecordsCount = progression.personalRecordsCount,
                    latestWeightKg = latestMeasurement?.weightKg
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    /**
     * Altera a meta semanal preservando a vigência já implementada: a semana corrente mantém a meta
     * antiga e o novo valor passa a valer na próxima. A regra continua morando no repositório.
     */
    fun setWeeklyGoal(goal: Int) {
        viewModelScope.launch {
            consistencyRepository.setWeeklyGoal(goal)
        }
    }

    /** As três conquistas desbloqueadas mais recentes — as demais continuam em Evolução. */
    private fun List<Achievement>.toRecentPreview(): List<Achievement> =
        filter { it.unlockedAt != null }
            .sortedByDescending { it.unlockedAt }
            .take(MAX_ACHIEVEMENT_PREVIEW)

    private companion object {
        const val MAX_ACHIEVEMENT_PREVIEW = 3
    }
}
