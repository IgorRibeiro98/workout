package com.example.feature.evolution.achievements

import com.example.domain.evolution.model.achievement.Achievement

data class AchievementsUiState(
    val isLoading: Boolean = true,
    val achievements: List<Achievement> = emptyList(),
    val unlockedCount: Int = 0,
    val error: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && achievements.isEmpty()
}
