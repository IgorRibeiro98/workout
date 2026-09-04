package com.example.domain.gamification

import com.example.domain.evolution.repository.AchievementEvaluationOrigin
import com.example.domain.evolution.repository.AchievementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AchievementReconciler(
    private val achievementRepository: AchievementRepository
) {
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.RECONCILIATION)
    }
}
