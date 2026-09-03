package com.example.domain.gamification.model

data class UserProgress(
    val currentLevel: Int,
    val totalXp: Int,
    val currentLevelXp: Int,
    val xpForNextLevel: Int
) {
    val progressPercentage: Float
        get() = if (xpForNextLevel > 0) currentLevelXp.toFloat() / xpForNextLevel else 0f
}
