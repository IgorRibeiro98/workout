package com.example.domain.evolution.repository

import com.example.domain.evolution.model.achievement.Achievement
import kotlinx.coroutines.flow.Flow

interface AchievementRepository {
    fun getAchievementsFlow(): Flow<List<Achievement>>
    suspend fun getAchievements(): List<Achievement>
}
