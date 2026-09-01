package com.example.domain.evolution.model.achievement

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: AchievementCategory,
    val unlockedAt: Long?,
    val progress: Float,
    val currentProgress: Int = 0,
    val targetProgress: Int = 1
)
