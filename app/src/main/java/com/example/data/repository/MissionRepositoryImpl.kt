package com.example.data.repository

import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.repository.ConsistencyRepository
import com.example.domain.gamification.GamificationEvents
import com.example.domain.gamification.XpCalculatorService
import com.example.domain.gamification.mission.MissionEvaluationContext
import com.example.domain.gamification.mission.MissionEvaluator
import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventMetadata
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.mission.MissionCatalog
import com.example.domain.gamification.model.mission.MissionCompletion
import com.example.domain.gamification.model.mission.MissionProgress
import com.example.domain.gamification.model.mission.MissionStatus
import com.example.domain.gamification.repository.GamificationEventRepository
import com.example.domain.gamification.repository.MissionEvaluationOrigin
import com.example.domain.gamification.repository.MissionRepository
import com.example.domain.gamification.repository.XpTransactionOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Missões sobre as autoridades que já existem.
 *
 * ```
 * ConsistencyRepository        -> treinos concluídos, semana vigente, meta semanal
 * GamificationEventRepository  -> conclusões registradas (idempotentes por missão + período)
 * XpCalculatorService          -> recompensa em XP pelo caminho canônico da T13.1
 * ```
 *
 * Nada de progresso é persistido: ele é sempre derivado dos fatos acima, então fechar o aplicativo
 * no meio de qualquer coisa não perde e nem duplica nada. O único registro novo é a conclusão da
 * missão — o fato que o histórico precisa preservar.
 */
class MissionRepositoryImpl(
    private val consistencyRepository: ConsistencyRepository,
    private val gamificationEventRepository: GamificationEventRepository,
    private val xpCalculatorService: XpCalculatorService,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = { System.currentTimeMillis() }
) : MissionRepository {

    private val _liveCompletions = MutableSharedFlow<MissionCompletion>()
    override val liveCompletions: SharedFlow<MissionCompletion> = _liveCompletions.asSharedFlow()

    override fun getMissionsFlow(): Flow<List<MissionProgress>> = combine(
        consistencyRepository.getWorkoutTimestampsFlow(),
        consistencyRepository.getWeeklyConsistenciesFlow(),
        gamificationEventRepository.observeEvents()
    ) { timestamps, weeklyConsistencies, events ->
        MissionEvaluator.evaluate(
            context(timestamps, weeklyConsistencies, events.toCompletions())
        )
    }

    override suspend fun getMissions(): List<MissionProgress> =
        MissionEvaluator.evaluate(buildContext())

    override suspend fun evaluateAndComplete(
        origin: MissionEvaluationOrigin
    ): List<MissionCompletion> {
        val evaluations = MissionEvaluator.evaluate(buildContext())
        val newCompletions = mutableListOf<MissionCompletion>()

        for (mission in evaluations) {
            if (mission.status != MissionStatus.COMPLETED) continue
            // Conclusões já registradas trazem `completedAt`: não há fato novo a criar.
            if (mission.completedAt != null) continue
            val definition = MissionCatalog.getDefinition(mission.missionId) ?: continue

            val completedAt = now()
            val event = GamificationEvents.missionCompleted(
                missionId = mission.missionId,
                periodKey = mission.periodKey,
                target = mission.target,
                rewardXp = definition.rewardXp,
                catalogVersion = MissionCatalog.CATALOG_VERSION,
                timestamp = completedAt
            )

            // A gravação é a barreira de idempotência: `missão + período` só existe uma vez, então
            // duas avaliações simultâneas (ou uma reconciliação depois) não geram segunda recompensa.
            val stored = gamificationEventRepository.record(event)
            if (!stored) continue

            xpCalculatorService.processEvent(event, origin.toXpOrigin())

            newCompletions += MissionCompletion(
                missionId = mission.missionId,
                periodKey = mission.periodKey,
                completedAt = completedAt,
                target = mission.target,
                rewardXp = definition.rewardXp
            )
        }

        if (origin == MissionEvaluationOrigin.LIVE) {
            newCompletions.forEach { _liveCompletions.emit(it) }
        }

        return newCompletions
    }

    private suspend fun buildContext(): MissionEvaluationContext = context(
        timestamps = consistencyRepository.getWorkoutTimestampsFlow().first(),
        weeklyConsistencies = consistencyRepository.getWeeklyConsistenciesFlow().first(),
        completions = gamificationEventRepository
            .getEventsOfType(GamificationEventType.MISSION_COMPLETED)
            .toCompletions()
    )

    private fun context(
        timestamps: List<Long>,
        weeklyConsistencies: List<WeeklyConsistency>,
        completions: List<MissionCompletion>
    ) = MissionEvaluationContext(
        completedWorkoutTimestamps = timestamps,
        weeklyConsistencies = weeklyConsistencies,
        completions = completions,
        referenceTimestamp = now(),
        zoneId = zoneId
    )

    private fun MissionEvaluationOrigin.toXpOrigin(): XpTransactionOrigin = when (this) {
        MissionEvaluationOrigin.LIVE -> XpTransactionOrigin.LIVE
        MissionEvaluationOrigin.RECONCILIATION -> XpTransactionOrigin.RECONCILIATION
    }

    /** Fatos de conclusão do histórico viram o que a avaliação precisa saber sobre o passado. */
    private fun List<GamificationEvent>.toCompletions(): List<MissionCompletion> =
        filter { it.type == GamificationEventType.MISSION_COMPLETED }
            .mapNotNull { event ->
                val missionId = event.metadata[GamificationEventMetadata.MISSION_ID]
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val periodKey = event.metadata[GamificationEventMetadata.MISSION_PERIOD_KEY]
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MissionCompletion(
                    missionId = missionId,
                    periodKey = periodKey,
                    completedAt = event.timestamp,
                    target = event.metadata[GamificationEventMetadata.MISSION_TARGET]?.toIntOrNull() ?: 0,
                    rewardXp = event.metadata[GamificationEventMetadata.MISSION_REWARD_XP]?.toIntOrNull() ?: 0
                )
            }
}
