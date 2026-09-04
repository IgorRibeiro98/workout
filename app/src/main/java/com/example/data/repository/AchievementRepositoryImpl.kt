package com.example.data.repository

import com.example.data.local.AchievementDao
import com.example.data.local.AchievementUnlockEntity
import com.example.data.local.GamificationEventDao
import com.example.data.local.WorkoutDao
import com.example.data.mapper.*
import com.example.domain.evolution.calculator.AchievementEvaluator
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementEvaluationContext
import com.example.domain.evolution.repository.AchievementEvaluationOrigin
import com.example.domain.evolution.model.achievement.AchievementUnlock
import com.example.domain.evolution.repository.AchievementRepository
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AchievementRepositoryImpl(
    private val achievementDao: AchievementDao,
    private val workoutDao: WorkoutDao,
    private val gamificationEventDao: GamificationEventDao,
    private val consistencyRepository: ConsistencyRepository,
    private val bodyMeasurementRepository: BodyMeasurementRepository
) : AchievementRepository {

    private val _liveUnlocks = MutableSharedFlow<AchievementUnlock>()
    override val liveUnlocks = _liveUnlocks.asSharedFlow()

    override fun getAchievementsFlow(): Flow<List<Achievement>> {
        val workoutsFlow = workoutDao.getAllCompletedSessionsWithDetailsFlow()
        val eventsFlow = gamificationEventDao.observeAll()
        val measurementsFlow = bodyMeasurementRepository.allMeasurements
        val consistencyProgressFlow = consistencyRepository.getConsistencyProgressFlow()
        val unlocksFlow = achievementDao.observeUnlocks()

        return combine(
            workoutsFlow,
            eventsFlow,
            measurementsFlow,
            consistencyProgressFlow,
            unlocksFlow
        ) { sessions, eventEntities, measurementEntities, consistencyProgress, unlockEntities ->
            val timestamps = sessions.map { it.session.finishedAt ?: it.session.startedAt }
            val events = eventEntities.mapNotNull { com.example.data.mapper.GamificationEventMapper.toDomain(it) }
            val measurements = measurementEntities.toDomain()
            
            val context = AchievementEvaluationContext(
                completedWorkoutsCount = timestamps.size,
                completedWorkoutsTimestamps = timestamps,
                gamificationEvents = events,
                measurements = measurements,
                consistencyProgress = consistencyProgress
            )

            val evaluations = AchievementEvaluator.evaluate(context)
            val unlocksMap = unlockEntities.associateBy { it.achievementId }

            evaluations.sortedBy { it.definition.order }.map { eval ->
                val unlock = unlocksMap[eval.definition.id]
                Achievement(
                    id = eval.definition.id,
                    title = eval.definition.title,
                    description = eval.definition.description,
                    icon = eval.definition.icon,
                    tier = eval.definition.tier,
                    category = eval.definition.category,
                    unlockedAt = unlock?.unlockedAt,
                    progress = (eval.currentProgress.toFloat() / eval.targetProgress.toFloat()).coerceIn(0f, 1f),
                    currentProgress = eval.currentProgress.coerceAtMost(eval.targetProgress),
                    targetProgress = eval.targetProgress
                )
            } // Wait, Achievement doesn't have definition, we can't sort by order. Let's fix mapping.
        }
    }

    override suspend fun getAchievements(): List<Achievement> {
        return getAchievementsFlow().first()
    }

    override suspend fun evaluateAndUnlock(origin: AchievementEvaluationOrigin): List<AchievementUnlock> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        val timestamps = sessions.map { it.session.finishedAt ?: it.session.startedAt }
        val events = gamificationEventDao.getAll().mapNotNull { com.example.data.mapper.GamificationEventMapper.toDomain(it) }
        val measurements = bodyMeasurementRepository.getAllMeasurementsSync().toDomain()
        val consistencyProgress = consistencyRepository.getConsistencyProgress()

        val context = AchievementEvaluationContext(
            completedWorkoutsCount = timestamps.size,
            completedWorkoutsTimestamps = timestamps,
            gamificationEvents = events,
            measurements = measurements,
            consistencyProgress = consistencyProgress
        )

        val evaluations = AchievementEvaluator.evaluate(context)
        val newUnlocks = mutableListOf<AchievementUnlock>()

        for (eval in evaluations) {
            if (eval.eligibleForUnlock) {
                val entity = AchievementUnlockEntity(
                    achievementId = eval.definition.id,
                    unlockedAt = eval.reachedAt ?: System.currentTimeMillis(),
                    triggerEventId = null,
                    definitionVersion = com.example.domain.evolution.model.achievement.AchievementCatalog.CATALOG_VERSION
                )
                val rowId = achievementDao.insertIfAbsent(entity)
                if (rowId != -1L) {
                    newUnlocks.add(AchievementUnlock(
                        achievementId = entity.achievementId,
                        unlockedAt = entity.unlockedAt,
                        triggerEventId = entity.triggerEventId
                    ))
                }
            }
        }
        
        if (origin == AchievementEvaluationOrigin.LIVE) {
            newUnlocks.forEach { _liveUnlocks.emit(it) }
        }
        
        return newUnlocks
    }
}
