package com.example.domain.evolution.model.achievement

data class AchievementEvaluation(
    val definition: AchievementDefinition,
    val currentProgress: Int,
    val targetProgress: Int,
    val eligibleForUnlock: Boolean,
    val reachedAt: Long?,
    val triggerEventId: String? = null
)
