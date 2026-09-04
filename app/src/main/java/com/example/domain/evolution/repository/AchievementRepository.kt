package com.example.domain.evolution.repository

import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementUnlock
import kotlinx.coroutines.flow.Flow

interface AchievementRepository {
    val liveUnlocks: kotlinx.coroutines.flow.SharedFlow<AchievementUnlock>
    fun getAchievementsFlow(): Flow<List<Achievement>>
    suspend fun getAchievements(): List<Achievement>
    suspend fun evaluateAndUnlock(origin: AchievementEvaluationOrigin = AchievementEvaluationOrigin.LIVE): List<AchievementUnlock>
}

enum class AchievementEvaluationOrigin {
    LIVE,
    RECONCILIATION
}
