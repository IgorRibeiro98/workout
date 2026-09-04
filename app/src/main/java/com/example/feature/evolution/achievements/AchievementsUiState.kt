package com.example.feature.evolution.achievements

import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCategory

data class AchievementsUiState(
    val isLoading: Boolean = true,
    val allAchievements: List<Achievement> = emptyList(),
    val displayedAchievements: List<Achievement> = emptyList(),
    val unlockedCount: Int = 0,
    val totalCount: Int = 0,
    val selectedCategory: AchievementCategory? = null,
    val selectedAchievementForDetail: Achievement? = null,
    val error: String? = null
) {
    val achievements: List<Achievement>
        get() = displayedAchievements

    val overallProgress: Float
        get() = if (totalCount > 0) (unlockedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f

    val isEmpty: Boolean
        get() = !isLoading && error == null && allAchievements.isEmpty()
}
