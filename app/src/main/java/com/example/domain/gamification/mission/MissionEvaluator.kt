package com.example.domain.gamification.mission

import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.domain.gamification.model.mission.MissionCatalog
import com.example.domain.gamification.model.mission.MissionCompletion
import com.example.domain.gamification.model.mission.MissionDefinition
import com.example.domain.gamification.model.mission.MissionPeriod
import com.example.domain.gamification.model.mission.MissionProgress
import com.example.domain.gamification.model.mission.MissionStatus
import com.example.domain.gamification.model.mission.MissionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tudo o que uma missão precisa observar, vindo pronto das autoridades existentes.
 *
 * @param completedWorkoutTimestamps instantes de sessões **COMPLETED** (histórico canônico de
 *        treinos). Sessões planejadas, em andamento, pausadas ou canceladas nunca chegam aqui.
 * @param weeklyConsistencies semanas calculadas pela consistência (meta vigente, treinos e
 *        veredito da semana). A missão de meta semanal apenas observa este resultado.
 * @param completions conclusões já registradas no histórico de fatos.
 */
data class MissionEvaluationContext(
    val completedWorkoutTimestamps: List<Long>,
    val weeklyConsistencies: List<WeeklyConsistency>,
    val completions: List<MissionCompletion>,
    val referenceTimestamp: Long,
    val zoneId: ZoneId = ZoneId.systemDefault()
)

/**
 * Autoridade única de avaliação de missões.
 *
 * É uma função pura sobre fatos já reconhecidos pelo domínio: nada aqui persiste, concede XP ou
 * dispara comemoração — quem faz isso é o repositório, a partir do resultado desta avaliação.
 *
 * Regras que este avaliador garante:
 * - a semana é a mesma da consistência ([ConsistencyCalculator.weekStart]);
 * - dois treinos no mesmo dia contam como um único dia;
 * - a meta semanal é copiada da consistência, nunca recalculada;
 * - uma conclusão já registrada congela alvo, recompensa e data — mudar o catálogo depois não
 *   reabre nem apaga o que já aconteceu.
 */
object MissionEvaluator {

    const val ALL_TIME_PERIOD_KEY = "all_time"

    /** Título exibido quando o catálogo atual não conhece mais uma missão concluída no passado. */
    const val UNKNOWN_MISSION_TITLE = "Missão concluída"

    /**
     * Chave do período: para missões semanais é exatamente a chave de semana da consistência
     * (epoch day da segunda-feira), evitando um segundo conceito de semana.
     */
    fun periodKeyFor(definition: MissionDefinition, weekStartEpochDay: Long): String =
        when (definition.period) {
            MissionPeriod.WEEKLY -> weekStartEpochDay.toString()
            MissionPeriod.ALL_TIME -> ALL_TIME_PERIOD_KEY
        }

    /**
     * Projeta o estado de todas as missões:
     *
     * 1. a instância do período atual de cada definição;
     * 2. a instância da semana anterior quando ela terminou sem conclusão (EXPIRED);
     * 3. as conclusões históricas já registradas.
     */
    fun evaluate(
        context: MissionEvaluationContext,
        definitions: List<MissionDefinition> = MissionCatalog.DEFINITIONS
    ): List<MissionProgress> {
        val currentWeekStart = ConsistencyCalculator.weekStartEpochDay(
            context.referenceTimestamp,
            context.zoneId
        )
        val previousWeekStart = LocalDate.ofEpochDay(currentWeekStart).minusWeeks(1).toEpochDay()

        val current = definitions
            .sortedBy { it.order }
            .map { evaluateInstance(it, currentWeekStart, context) }

        // A semana anterior só produz missões expiradas quando a consistência realmente a contava:
        // em uma instalação nova não existe semana passada perdida.
        val previousWeekCounted = context.weeklyConsistencies.any {
            it.weekStartEpochDay == previousWeekStart && it.status != WeeklyConsistencyStatus.NOT_COUNTED
        }
        val expiredPrevious = if (previousWeekCounted) {
            definitions
                .filter { it.period == MissionPeriod.WEEKLY }
                .sortedBy { it.order }
                .map { evaluateInstance(it, previousWeekStart, context) }
                // Só interessa o que expirou: uma semana passada cujo alvo foi atingido sem
                // registro não é recompensada retroativamente, e some da lista em silêncio.
                .filter { it.status == MissionStatus.EXPIRED }
        } else {
            emptyList()
        }

        val evaluatedKeys = (current + expiredPrevious).map { it.missionId to it.periodKey }.toSet()

        val history = context.completions
            .filterNot { (it.missionId to it.periodKey) in evaluatedKeys }
            .sortedByDescending { it.completedAt }
            .map { it.toHistoricalProgress(context.zoneId) }

        return current + expiredPrevious + history
    }

    /**
     * Estado de uma definição em um período específico.
     *
     * O período é identificado por [weekStartEpochDay] para missões semanais; missões acumuladas
     * ignoram esse valor porque não têm prazo.
     */
    fun evaluateInstance(
        definition: MissionDefinition,
        weekStartEpochDay: Long,
        context: MissionEvaluationContext
    ): MissionProgress {
        val periodKey = periodKeyFor(definition, weekStartEpochDay)
        val completion = context.completions.firstOrNull {
            it.missionId == definition.id && it.periodKey == periodKey
        }
        val periodEndsAt = periodEndsAt(definition, weekStartEpochDay, context.zoneId)

        if (completion != null) {
            // Conclusão já registrada: o histórico manda, não a avaliação de agora.
            return MissionProgress(
                missionId = definition.id,
                title = definition.title,
                description = definition.description,
                periodKey = periodKey,
                progress = completion.target,
                target = completion.target,
                status = MissionStatus.COMPLETED,
                rewardXp = completion.rewardXp,
                completedAt = completion.completedAt,
                periodEndsAt = periodEndsAt
            )
        }

        val measurement = measure(definition, weekStartEpochDay, context)
        val periodEnded = periodEndsAt != null && context.referenceTimestamp > periodEndsAt
        val status = when {
            measurement.reached -> MissionStatus.COMPLETED
            // Sem alvo mensurável (semana sem meta vigente) não há o que expirar.
            measurement.target <= 0 -> MissionStatus.ACTIVE
            periodEnded -> MissionStatus.EXPIRED
            else -> MissionStatus.ACTIVE
        }

        return MissionProgress(
            missionId = definition.id,
            title = definition.title,
            description = definition.description,
            periodKey = periodKey,
            progress = if (measurement.target > 0) {
                measurement.progress.coerceAtMost(measurement.target)
            } else {
                measurement.progress
            },
            target = measurement.target,
            status = status,
            rewardXp = definition.rewardXp,
            completedAt = null,
            periodEndsAt = periodEndsAt
        )
    }

    private data class Measurement(
        val progress: Int,
        val target: Int,
        val reached: Boolean
    )

    private fun measure(
        definition: MissionDefinition,
        weekStartEpochDay: Long,
        context: MissionEvaluationContext
    ): Measurement = when (definition.type) {
        MissionType.WORKOUT_COUNT -> {
            val target = definition.target ?: 0
            val progress = context.workoutsInWeek(weekStartEpochDay).size
            Measurement(progress, target, target > 0 && progress >= target)
        }

        MissionType.TRAINING_DAYS -> {
            val target = definition.target ?: 0
            // Dois treinos no mesmo dia são um único dia de treino.
            val progress = context.workoutsInWeek(weekStartEpochDay).distinct().size
            Measurement(progress, target, target > 0 && progress >= target)
        }

        MissionType.WEEKLY_GOAL -> {
            // A meta e o veredito da semana pertencem à consistência: aqui só copiamos.
            val week = context.weeklyConsistencies.firstOrNull {
                it.weekStartEpochDay == weekStartEpochDay
            }
            val target = definition.target ?: week?.goal ?: 0
            val progress = week?.completedWorkouts ?: 0
            Measurement(progress, target, week?.status == WeeklyConsistencyStatus.COMPLETED)
        }

        MissionType.TOTAL_WORKOUTS -> {
            val target = definition.target ?: 0
            val progress = context.completedWorkoutTimestamps.size
            Measurement(progress, target, target > 0 && progress >= target)
        }
    }

    /** Datas locais das sessões concluídas dentro da semana pedida. */
    private fun MissionEvaluationContext.workoutsInWeek(weekStartEpochDay: Long): List<LocalDate> =
        completedWorkoutTimestamps
            .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            .filter { ConsistencyCalculator.weekStart(it).toEpochDay() == weekStartEpochDay }

    /** Último instante do período (domingo 23:59:59.999); `null` quando a missão não expira. */
    private fun periodEndsAt(
        definition: MissionDefinition,
        weekStartEpochDay: Long,
        zoneId: ZoneId
    ): Long? = when (definition.period) {
        MissionPeriod.WEEKLY -> LocalDate.ofEpochDay(weekStartEpochDay)
            .plusWeeks(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli() - 1

        MissionPeriod.ALL_TIME -> null
    }

    /**
     * Conclusão histórica projetada a partir do fato registrado.
     *
     * O texto vem do catálogo atual quando ele ainda conhece a missão; o que aconteceu (alvo,
     * recompensa e data) vem sempre do fato, nunca de uma reavaliação.
     */
    private fun MissionCompletion.toHistoricalProgress(zoneId: ZoneId): MissionProgress {
        val definition = MissionCatalog.getDefinition(missionId)
        val weekStartEpochDay = periodKey.toLongOrNull()
        return MissionProgress(
            missionId = missionId,
            title = definition?.title ?: UNKNOWN_MISSION_TITLE,
            description = definition?.description.orEmpty(),
            periodKey = periodKey,
            progress = target,
            target = target,
            status = MissionStatus.COMPLETED,
            rewardXp = rewardXp,
            completedAt = completedAt,
            periodEndsAt = weekStartEpochDay?.let {
                LocalDate.ofEpochDay(it).plusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
            }
        )
    }
}
