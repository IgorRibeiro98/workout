package com.example.data.repository

import com.example.data.local.AchievementDao
import com.example.data.local.AchievementUnlockEntity
import com.example.data.local.GamificationEventDao
import com.example.data.local.WorkoutDao
import com.example.data.mapper.*
import com.example.domain.evolution.calculator.AchievementEvaluator
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementEvaluation
import com.example.domain.evolution.model.achievement.AchievementEvaluationContext
import com.example.domain.evolution.repository.AchievementEvaluationOrigin
import com.example.domain.evolution.model.achievement.AchievementUnlock
import com.example.domain.evolution.repository.AchievementRepository
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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
        // A projeção, e não o grafo: a avaliação só usa **quando** cada treino foi concluído.
        // `getAllCompletedSessionsWithDetailsFlow()` carregava sessões, exercícios e séries de todo
        // o histórico para produzir esta mesma lista de `Long` — e era reemitido a cada série
        // concluída, porque o grafo depende de `set_logs`.
        val workoutsFlow = workoutDao.getCompletedSessionTimestampsFlow().distinctUntilChanged()
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
        ) { timestamps, eventEntities, measurementEntities, consistencyProgress, unlockEntities ->
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
            }
        }
    }

    /**
     * A lista de conquistas **agora**, por consultas diretas.
     *
     * Era `getAchievementsFlow().first()`: montar um `combine` de cinco fontes observáveis,
     * assinar todas, esperar a primeira emissão de cada uma e descartar o pipeline inteiro —
     * inclusive o do histórico completo. As mesmas fontes têm leitura suspensa, e é ela que a
     * pergunta "quais são as conquistas?" precisa.
     */
    override suspend fun getAchievements(): List<Achievement> {
        val unlocksMap = achievementDao.getUnlocks().associateBy { it.achievementId }
        return evaluate().sortedBy { it.definition.order }.map { eval ->
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
        }
    }

    /**
     * O contexto de avaliação lido do banco, uma vez.
     *
     * O histórico entra como a projeção de timestamps: é tudo o que a avaliação usa dele, e
     * carregar o grafo completo aqui pesava em cada medida corporal registrada — que é um dos
     * gatilhos de [evaluateAndUnlock].
     */
    private suspend fun evaluate(): List<AchievementEvaluation> {
        val timestamps = workoutDao.getCompletedSessionTimestamps()
        val events = gamificationEventDao.getAll().mapNotNull { com.example.data.mapper.GamificationEventMapper.toDomain(it) }
        val measurements = bodyMeasurementRepository.getAllMeasurementsSync().toDomain()
        val consistencyProgress = consistencyRepository.getConsistencyProgress()

        return AchievementEvaluator.evaluate(
            AchievementEvaluationContext(
                completedWorkoutsCount = timestamps.size,
                completedWorkoutsTimestamps = timestamps,
                gamificationEvents = events,
                measurements = measurements,
                consistencyProgress = consistencyProgress
            )
        )
    }

    override suspend fun evaluateAndUnlock(origin: AchievementEvaluationOrigin): List<AchievementUnlock> {
        val evaluations = evaluate()
        val newUnlocks = mutableListOf<AchievementUnlock>()

        for (eval in evaluations) {
            if (eval.eligibleForUnlock) {
                val unlockedTime = eval.reachedAt ?: continue

                val entity = AchievementUnlockEntity(
                    achievementId = eval.definition.id,
                    unlockedAt = unlockedTime,
                    triggerEventId = eval.triggerEventId,
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
