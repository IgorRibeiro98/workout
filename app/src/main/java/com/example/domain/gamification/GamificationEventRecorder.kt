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
    private val workoutTimestampsProvider: suspend () -> List<Long>,
    private val weeklyGoalProvider: suspend () -> Int,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : GamificationEventPublisher {

    override suspend fun publish(event: GamificationEvent): Boolean {
        val stored = try {
            repository.record(event)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        if (stored && event.type == GamificationEventType.WORKOUT_COMPLETED) {
            evaluateConsistency(event.timestamp)
        }
        return stored
    }

    private suspend fun evaluateConsistency(referenceTimestamp: Long) {
        try {
            val derived = ConsistencyMilestoneEvaluator.evaluate(
                workoutTimestamps = workoutTimestampsProvider(),
                weeklyGoal = weeklyGoalProvider(),
                referenceTimestamp = referenceTimestamp,
                zoneId = zoneId
            )
            derived.forEach { repository.record(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
