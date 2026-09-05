package com.example.domain.gamification

import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.domain.gamification.mission.MissionEvaluationContext
import com.example.domain.gamification.mission.MissionEvaluator
import com.example.domain.gamification.model.mission.MissionCatalog
import com.example.domain.gamification.model.mission.MissionCompletion
import com.example.domain.gamification.model.mission.MissionDefinition
import com.example.domain.gamification.model.mission.MissionPeriod
import com.example.domain.gamification.model.mission.MissionProgress
import com.example.domain.gamification.model.mission.MissionStatus
import com.example.domain.gamification.model.mission.MissionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * T13.5 — regras de avaliação das missões.
 *
 * Os testes fixam a semana e o fuso para que o resultado não dependa do dia em que rodam: o que
 * está sob teste é a regra, não o calendário da máquina.
 */
class MissionEvaluatorTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    /** Semana 2026-W36: segunda 31/08/2026 a domingo 06/09/2026. */
    private val monday: LocalDate = LocalDate.of(2026, 8, 31)
    private val tuesday: LocalDate = monday.plusDays(1)
    private val wednesday: LocalDate = monday.plusDays(2)
    private val previousMonday: LocalDate = monday.minusWeeks(1)

    private val weekStart = monday.toEpochDay()
    private val previousWeekStart = previousMonday.toEpochDay()

    private val workoutsMission = MissionCatalog.getDefinition("weekly_workouts_3")!!
    private val trainingDaysMission = MissionCatalog.getDefinition("weekly_training_days_3")!!
    private val weeklyGoalMission = MissionCatalog.getDefinition("weekly_goal")!!
    private val totalWorkoutsMission = MissionCatalog.getDefinition("total_workouts_10")!!

    // ---------------------------------------------------------------------------------------
    // Progresso a partir de treinos reais
    // ---------------------------------------------------------------------------------------

    @Test
    fun `missao de treinos na semana progride ate concluir`() {
        val partial = evaluate(workouts = listOf(at(monday), at(tuesday)))
            .mission(workoutsMission.id)

        assertEquals(2, partial.progress)
        assertEquals(3, partial.target)
        assertEquals(MissionStatus.ACTIVE, partial.status)

        val completed = evaluate(workouts = listOf(at(monday), at(tuesday), at(wednesday)))
            .mission(workoutsMission.id)

        assertEquals(MissionStatus.COMPLETED, completed.status)
        assertEquals(3, completed.progress)
    }

    @Test
    fun `treinos fora da semana atual nao contam para a missao semanal`() {
        val mission = evaluate(
            workouts = listOf(at(previousMonday), at(previousMonday.plusDays(1)), at(monday))
        ).mission(workoutsMission.id)

        assertEquals(1, mission.progress)
        assertEquals(MissionStatus.ACTIVE, mission.status)
    }

    @Test
    fun `dois treinos no mesmo dia contam como um unico dia`() {
        val mission = evaluate(
            workouts = listOf(at(monday, hour = 7), at(monday, hour = 19), at(tuesday))
        ).mission(trainingDaysMission.id)

        assertEquals("Dois treinos na segunda são um dia", 2, mission.progress)
        assertEquals(3, mission.target)
        assertEquals(MissionStatus.ACTIVE, mission.status)
    }

    @Test
    fun `missao de meta semanal copia meta e veredito da consistencia`() {
        val inProgress = evaluate(
            weeklyConsistencies = listOf(
                week(weekStart, goal = 4, completed = 3, status = WeeklyConsistencyStatus.IN_PROGRESS)
            )
        ).mission(weeklyGoalMission.id)

        assertEquals("A meta é a da consistência, não a do catálogo", 4, inProgress.target)
        assertEquals(3, inProgress.progress)
        assertEquals(MissionStatus.ACTIVE, inProgress.status)

        val reached = evaluate(
            weeklyConsistencies = listOf(
                week(weekStart, goal = 4, completed = 4, status = WeeklyConsistencyStatus.COMPLETED)
            )
        ).mission(weeklyGoalMission.id)

        assertEquals(MissionStatus.COMPLETED, reached.status)
        assertEquals(4, reached.target)
    }

    @Test
    fun `marco acumulado usa historico completo`() {
        val almost = evaluate(workouts = List(9) { at(monday.minusWeeks(it.toLong())) })
            .mission(totalWorkoutsMission.id)

        assertEquals(9, almost.progress)
        assertEquals(10, almost.target)
        assertEquals(MissionStatus.ACTIVE, almost.status)

        val done = evaluate(workouts = List(10) { at(monday.minusWeeks(it.toLong())) })
            .mission(totalWorkoutsMission.id)

        assertEquals(MissionStatus.COMPLETED, done.status)
        assertNull("Marco acumulado não tem prazo", done.periodEndsAt)
    }

    // ---------------------------------------------------------------------------------------
    // Períodos, expiração e histórico
    // ---------------------------------------------------------------------------------------

    @Test
    fun `semana anterior sem conclusao expira`() {
        val missions = evaluate(
            workouts = emptyList(),
            weeklyConsistencies = listOf(
                week(previousWeekStart, goal = 3, completed = 1, status = WeeklyConsistencyStatus.MISSED),
                week(weekStart, goal = 3, completed = 0, status = WeeklyConsistencyStatus.IN_PROGRESS)
            )
        )

        val expired = missions.first {
            it.missionId == workoutsMission.id && it.periodKey == previousWeekStart.toString()
        }
        assertEquals(MissionStatus.EXPIRED, expired.status)

        val current = missions.mission(workoutsMission.id)
        assertEquals("A semana atual continua ativa", MissionStatus.ACTIVE, current.status)
    }

    @Test
    fun `conclusao anterior permanece concluida na virada da semana`() {
        val completion = MissionCompletion(
            missionId = workoutsMission.id,
            periodKey = previousWeekStart.toString(),
            completedAt = at(previousMonday.plusDays(3)),
            target = 3,
            rewardXp = workoutsMission.rewardXp
        )

        val missions = evaluate(
            workouts = emptyList(),
            completions = listOf(completion),
            weeklyConsistencies = listOf(
                week(previousWeekStart, goal = 3, completed = 3, status = WeeklyConsistencyStatus.COMPLETED),
                week(weekStart, goal = 3, completed = 0, status = WeeklyConsistencyStatus.IN_PROGRESS)
            )
        )

        val history = missions.first {
            it.missionId == workoutsMission.id && it.periodKey == previousWeekStart.toString()
        }
        assertEquals(MissionStatus.COMPLETED, history.status)
        assertEquals(completion.completedAt, history.completedAt)

        val current = missions.mission(workoutsMission.id)
        assertEquals("A nova semana começa do zero", 0, current.progress)
        assertEquals(MissionStatus.ACTIVE, current.status)
        assertNull(current.completedAt)
        assertTrue("O histórico continua na lista", missions.size > 1)
    }

    @Test
    fun `conclusao registrada congela alvo e recompensa mesmo se o catalogo mudar`() {
        val completion = MissionCompletion(
            missionId = workoutsMission.id,
            periodKey = weekStart.toString(),
            completedAt = at(tuesday),
            target = 3,
            rewardXp = 150
        )

        // Catálogo futuro: mesma missão, alvo e recompensa maiores.
        val futureCatalog = listOf(
            MissionDefinition(
                id = workoutsMission.id,
                title = "Constância em alta",
                description = "Complete 5 treinos nesta semana.",
                type = MissionType.WORKOUT_COUNT,
                period = MissionPeriod.WEEKLY,
                target = 5,
                rewardXp = 400,
                order = 1
            )
        )

        val mission = MissionEvaluator.evaluate(
            context(workouts = listOf(at(monday)), completions = listOf(completion)),
            definitions = futureCatalog
        ).mission(workoutsMission.id)

        assertEquals(MissionStatus.COMPLETED, mission.status)
        assertEquals("O alvo cumprido é o do dia da conclusão", 3, mission.target)
        assertEquals("A recompensa concedida não é reescrita", 150, mission.rewardXp)
    }

    @Test
    fun `conclusao de missao removida do catalogo continua no historico`() {
        val completion = MissionCompletion(
            missionId = "missao_aposentada",
            periodKey = previousWeekStart.toString(),
            completedAt = at(previousMonday),
            target = 2,
            rewardXp = 90
        )

        val history = MissionEvaluator.evaluate(
            context(completions = listOf(completion))
        ).first { it.missionId == "missao_aposentada" }

        assertEquals(MissionStatus.COMPLETED, history.status)
        assertEquals(90, history.rewardXp)
        assertEquals(MissionEvaluator.UNKNOWN_MISSION_TITLE, history.title)
    }

    @Test
    fun `instalacao nova nao inventa missoes expiradas`() {
        val missions = evaluate(
            workouts = emptyList(),
            weeklyConsistencies = listOf(
                week(weekStart, goal = 3, completed = 0, status = WeeklyConsistencyStatus.IN_PROGRESS)
            )
        )

        assertTrue(
            "Sem semana anterior contabilizada não existe missão expirada",
            missions.none { it.status == MissionStatus.EXPIRED }
        )
    }

    @Test
    fun `prazo da missao semanal termina no domingo`() {
        val mission = evaluate().mission(workoutsMission.id)
        val endsAt = mission.periodEndsAt
        assertNotNull(endsAt)

        val endDate = java.time.Instant.ofEpochMilli(endsAt!!).atZone(zone).toLocalDate()
        assertEquals(monday.plusDays(6), endDate)
    }

    // ---------------------------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------------------------

    private fun at(date: LocalDate, hour: Int = 10): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun week(
        weekStartEpochDay: Long,
        goal: Int,
        completed: Int,
        status: WeeklyConsistencyStatus
    ) = WeeklyConsistency(
        weekStartEpochDay = weekStartEpochDay,
        goal = goal,
        completedWorkouts = completed,
        status = status
    )

    private fun context(
        workouts: List<Long> = emptyList(),
        weeklyConsistencies: List<WeeklyConsistency> = listOf(
            week(weekStart, goal = 3, completed = workouts.size, status = WeeklyConsistencyStatus.IN_PROGRESS)
        ),
        completions: List<MissionCompletion> = emptyList(),
        reference: Long = at(wednesday, hour = 18)
    ) = MissionEvaluationContext(
        completedWorkoutTimestamps = workouts,
        weeklyConsistencies = weeklyConsistencies,
        completions = completions,
        referenceTimestamp = reference,
        zoneId = zone
    )

    private fun evaluate(
        workouts: List<Long> = emptyList(),
        weeklyConsistencies: List<WeeklyConsistency> = listOf(
            week(weekStart, goal = 3, completed = workouts.size, status = WeeklyConsistencyStatus.IN_PROGRESS)
        ),
        completions: List<MissionCompletion> = emptyList()
    ): List<MissionProgress> = MissionEvaluator.evaluate(
        context(workouts, weeklyConsistencies, completions)
    )

    /** Instância da semana corrente da missão pedida. */
    private fun List<MissionProgress>.mission(id: String): MissionProgress =
        first { it.missionId == id && it.periodKey != previousWeekStart.toString() }
}
