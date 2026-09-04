package com.example.domain.evolution.model.achievement

data class AchievementUnlock(
    val achievementId: String,
    val unlockedAt: Long,
    val triggerEventId: String? = null
)
