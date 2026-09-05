package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.repository.GamificationEventRepository
import java.time.ZoneId

/**
 * Observador da gamificação: recebe os fatos publicados pelo restante do aplicativo, persiste-os e
 * deriva os fatos de consistência que só existem quando olhamos o histórico inteiro
 * (meta semanal e marcos de streak).
 *
 * Nada aqui altera o fluxo de treino: qualquer falha é contida para que a execução do usuário
 * continue exatamente como antes.
 */
class GamificationEventRecorder(
    private val repository: GamificationEventRepository,
    private val xpCalculatorService: XpCalculatorService,
    private val achievementRepository: com.example.domain.evolution.repository.AchievementRepository? = null,
    private val missionRepository: com.example.domain.gamification.repository.MissionRepository? = null,
    private val workoutTimestampsProvider: suspend () -> List<Long>,
    private val weeklyGoalProvider: suspend () -> Int,
    private val goalSnapshotsProvider: (suspend () -> List<com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot>)? = null,
    private val trackingStartedAtProvider: (suspend () -> Long?)? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : GamificationEventPublisher {

    override suspend fun publish(event: GamificationEvent): Boolean {
        val stored = try {
            repository.record(event)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        if (stored) {
            try {
                xpCalculatorService.processEvent(event)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (event.type == GamificationEventType.WORKOUT_COMPLETED) {
                evaluateConsistency(event.timestamp)
            }
            
            try {
                achievementRepository?.evaluateAndUnlock(com.example.domain.evolution.repository.AchievementEvaluationOrigin.LIVE)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // As missões observam o mesmo histórico persistido; avaliá-las aqui apenas antecipa o
            // que a reconciliação faria na próxima abertura, com a mesma garantia de idempotência.
            // Só o fim de um treino pode mover uma missão, então nada é reavaliado a cada série.
            try {
                if (event.type == GamificationEventType.WORKOUT_COMPLETED) {
                    missionRepository?.evaluateAndComplete(
                        com.example.domain.gamification.repository.MissionEvaluationOrigin.LIVE
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return stored
    }

    private suspend fun evaluateConsistency(referenceTimestamp: Long) {
        try {
            val snapshots = goalSnapshotsProvider?.invoke() ?: emptyList()
            val trackingStartedAt = trackingStartedAtProvider?.invoke()
            val derived = ConsistencyMilestoneEvaluator.evaluate(
                workoutTimestamps = workoutTimestampsProvider(),
                weeklyGoal = weeklyGoalProvider(),
                goalSnapshots = snapshots,
                referenceTimestamp = referenceTimestamp,
                trackingStartedAtEpochDay = trackingStartedAt,
                zoneId = zoneId
            )
            derived.forEach { 
                val derivedStored = repository.record(it)
                if (derivedStored) {
                    xpCalculatorService.processEvent(it)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
