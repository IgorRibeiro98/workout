package com.example.domain.evolution.model.achievement

data class AchievementDefinition(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: AchievementCategory,
    val tier: AchievementTier,
    val target: Int,
    val order: Int
)
