package com.example

import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ConsistencyEvolutionTest {

    private val zoneId = ZoneId.of("UTC")

    @Test
    fun testNewUser_zeroWorkouts() {
        val summary = ConsistencyCalculator.calculateConsistencySummary(
            timestamps = emptyList(),
            zoneId = zoneId
        )

        assertEquals(0, summary.totalSessions)
        assertEquals(0, summary.currentStreak)
        assertEquals(0, summary.longestStreak)
        assertEquals(0f, summary.averageSessionsPerWeek, 0.01f)
        assertNull(summary.lastWorkoutDate)
    }

    @Test
    fun testWeeklyStreak_threeConsistentWeeks() {
        // Week 1 (Monday 03/08/2026 to Sunday 09/08/2026): 3 workouts (goal 3)
        // Week 2 (Monday 10/08/2026 to Sunday 16/08/2026): 3 workouts (goal 3)
        // Week 3 (Monday 17/08/2026 to Sunday 23/08/2026): 3 workouts (goal 3)
        val w1 = listOf(
            LocalDate.of(2026, 8, 3).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 5).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 7).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        val w2 = listOf(
            LocalDate.of(2026, 8, 10).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 12).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 14).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        val w3 = listOf(
            LocalDate.of(2026, 8, 17).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 19).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 21).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )

        val refDate = LocalDate.of(2026, 8, 22) // Inside week 3
        val weekly = ConsistencyCalculator.calculateWeeklyConsistencies(
            timestamps = w1 + w2 + w3,
            defaultGoal = 3,
            referenceDate = refDate,
            zoneId = zoneId
        )
        val progress = ConsistencyCalculator.calculateProgress(weekly, refDate)

        assertEquals(3, progress.currentStreakWeeks)
        assertEquals(3, progress.longestStreakWeeks)
        assertEquals(WeeklyConsistencyStatus.COMPLETED, progress.currentWeekStatus)
    }

    @Test
    fun testCurrentWeekInProgress_doesNotBreakStreak() {
        // Week 1: 3 workouts (goal 3) -> COMPLETED
        // Week 2: 3 workouts (goal 3) -> COMPLETED
        // Week 3 (current week): 1 workout (goal 3) -> IN_PROGRESS (should preserve streak = 2)
        val w1 = listOf(
            LocalDate.of(2026, 8, 3).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 5).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 7).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        val w2 = listOf(
            LocalDate.of(2026, 8, 10).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 12).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 14).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        val w3 = listOf(
            LocalDate.of(2026, 8, 17).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )

        val refDate = LocalDate.of(2026, 8, 18) // Tuesday of week 3
        val weekly = ConsistencyCalculator.calculateWeeklyConsistencies(
            timestamps = w1 + w2 + w3,
            defaultGoal = 3,
            referenceDate = refDate,
            zoneId = zoneId
        )
        val progress = ConsistencyCalculator.calculateProgress(weekly, refDate)

        assertEquals(2, progress.currentStreakWeeks)
        assertEquals(2, progress.longestStreakWeeks)
        assertEquals(1, progress.currentWeekCompleted)
        assertEquals(3, progress.currentWeekGoal)
        assertEquals(WeeklyConsistencyStatus.IN_PROGRESS, progress.currentWeekStatus)
    }

    @Test
    fun testGoalChange_historicalStability() {
        // Week 1: goal was 3, did 3 -> COMPLETED
        // Week 2: goal changed to 4, did 4 -> COMPLETED
        // Week 3: did 3, but goal is 4 -> IN PROGRESS / MISSED
        val w1Start = LocalDate.of(2026, 8, 3)
        val w2Start = LocalDate.of(2026, 8, 10)

        val goalSnapshots = listOf(
            WeeklyGoalSnapshot(w1Start.toEpochDay(), 3),
            WeeklyGoalSnapshot(w2Start.toEpochDay(), 4)
        )

        val w1 = listOf(
            LocalDate.of(2026, 8, 3).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 5).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 7).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        val w2 = listOf(
            LocalDate.of(2026, 8, 10).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 11).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 13).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 15).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )

        val weekly = ConsistencyCalculator.calculateWeeklyConsistencies(
            timestamps = w1 + w2,
            goalSnapshots = goalSnapshots,
            defaultGoal = 3,
            referenceDate = LocalDate.of(2026, 8, 16),
            zoneId = zoneId
        )

        assertEquals(3, weekly[0].goal)
        assertEquals(3, weekly[0].completedWorkouts)
        assertEquals(WeeklyConsistencyStatus.COMPLETED, weekly[0].status)

        assertEquals(4, weekly[1].goal)
        assertEquals(4, weekly[1].completedWorkouts)
        assertEquals(WeeklyConsistencyStatus.COMPLETED, weekly[1].status)
    }

    @Test
    fun testWeeklyAverage_fourWorkoutsInTwoWeeks() {
        val day1 = LocalDate.of(2026, 8, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day2 = LocalDate.of(2026, 8, 5).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day3 = LocalDate.of(2026, 8, 9).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day4 = LocalDate.of(2026, 8, 14).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val summary = ConsistencyCalculator.calculateConsistencySummary(
            timestamps = listOf(day1, day2, day3, day4),
            zoneId = zoneId
        )

        assertEquals(4, summary.totalSessions)
        assertEquals(2.0f, summary.averageSessionsPerWeek, 0.1f)
    }

    @Test
    fun testFrequencyHistory_groupingByWeek() {
        val day1 = LocalDate.of(2026, 8, 3).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day2 = LocalDate.of(2026, 8, 5).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day3 = LocalDate.of(2026, 8, 10).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val history = ConsistencyCalculator.calculateFrequencyHistory(
            timestamps = listOf(day1, day2, day3),
            zoneId = zoneId
        )

        assertEquals(2, history.size)
        assertEquals(2, history[0].sessions)
        assertEquals(1, history[1].sessions)
    }
}
