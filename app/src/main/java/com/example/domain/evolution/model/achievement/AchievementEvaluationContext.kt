package com.example.domain.evolution.model.achievement

import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.performance.PersonalRecord

data class AchievementEvaluationContext(
    val completedWorkoutsCount: Int,
    val completedWorkoutsTimestamps: List<Long>,
    val gamificationEvents: List<com.example.domain.gamification.model.GamificationEvent>,
    val measurements: List<BodyMeasurement>,
    val consistencyProgress: ConsistencyProgress?
)
