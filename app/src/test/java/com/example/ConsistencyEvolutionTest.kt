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
        val trackingStart = LocalDate.now(zoneId).toEpochDay()
        val summary = ConsistencyCalculator.calculateConsistencySummary(
            timestamps = emptyList(),
            goalSnapshots = listOf(WeeklyGoalSnapshot(trackingStart, 3)),
            trackingStartedAtEpochDay = trackingStart,
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
            
            referenceDate = refDate,
            goalSnapshots = listOf(WeeklyGoalSnapshot(LocalDate.of(2026, 8, 1).toEpochDay(), 3)),
            trackingStartedAtEpochDay = LocalDate.of(2026, 8, 1).toEpochDay(),
            zoneId = zoneId
        )
        val progress = ConsistencyCalculator.calculateProgress(weekly, refDate)

        assertEquals(3, progress.currentStreakWeeks)
        assertEquals(3, progress.longestStreakWeeks)
        assertEquals(WeeklyConsistencyStatus.COMPLETED, progress.currentWeekStatus)
    }

    @Test
    fun testCurrentWeekInProgress_doesNotBreakStreak() {
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
            
            referenceDate = refDate,
            goalSnapshots = listOf(WeeklyGoalSnapshot(LocalDate.of(2026, 8, 1).toEpochDay(), 3)),
            trackingStartedAtEpochDay = LocalDate.of(2026, 8, 1).toEpochDay(),
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
            referenceDate = LocalDate.of(2026, 8, 16),
            goalSnapshots = goalSnapshots,
            trackingStartedAtEpochDay = LocalDate.of(2026, 8, 3).toEpochDay(),
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
            goalSnapshots = listOf(WeeklyGoalSnapshot(LocalDate.of(2026, 7, 30).toEpochDay(), 3)),
            trackingStartedAtEpochDay = LocalDate.of(2026, 7, 30).toEpochDay(),
            zoneId = zoneId
        )

        assertEquals(4, summary.totalSessions)
        assertEquals(2.0f, summary.averageSessionsPerWeek, 0.1f)
    }

    @Test
    fun testFirstWeekPartialMissed_doesNotCount() {
        // Starts on Friday 2026-08-07, default goal is 3. Week is partial and they only do 1.
        val startDay = LocalDate.of(2026, 8, 7)
        val w1 = listOf(
            LocalDate.of(2026, 8, 8).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        // Next week they do 3.
        val w2 = listOf(
            LocalDate.of(2026, 8, 10).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 12).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            LocalDate.of(2026, 8, 14).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )

        val refDate = LocalDate.of(2026, 8, 16)
        val weekly = ConsistencyCalculator.calculateWeeklyConsistencies(
            timestamps = w1 + w2,
            
            referenceDate = refDate,
            goalSnapshots = listOf(WeeklyGoalSnapshot(startDay.toEpochDay(), 3)),
            trackingStartedAtEpochDay = startDay.toEpochDay(),
            zoneId = zoneId
        )

        assertEquals(2, weekly.size)
        assertEquals(WeeklyConsistencyStatus.NOT_COUNTED, weekly[0].status)
        assertEquals(WeeklyConsistencyStatus.COMPLETED, weekly[1].status)

        val progress = ConsistencyCalculator.calculateProgress(weekly, refDate)
        assertEquals(1, progress.currentStreakWeeks)
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

    @Test
    fun testTimezone_workoutAtLateNight() {
        // A workout performed at 23:30 local time on a Sunday (end of week).
        val localSunday = LocalDate.of(2026, 8, 9)
        val zoneIdLocal = ZoneId.of("America/Sao_Paulo")
        val timestamp = localSunday.atTime(23, 30).atZone(zoneIdLocal).toInstant().toEpochMilli()

        // Evaluated in the same timezone, it should count towards that week (Monday 2026-08-03).
        val historyLocal = ConsistencyCalculator.calculateFrequencyHistory(
            timestamps = listOf(timestamp),
            zoneId = zoneIdLocal
        )
        val expectedWeekStartLocal = LocalDate.of(2026, 8, 3).atStartOfDay(zoneIdLocal).toInstant().toEpochMilli()
        assertEquals(expectedWeekStartLocal, historyLocal[0].date)

        // If evaluated in UTC (e.g. user traveled), it will be 02:30 on Monday 2026-08-10.
        val zoneIdUtc = ZoneId.of("UTC")
        val historyUtc = ConsistencyCalculator.calculateFrequencyHistory(
            timestamps = listOf(timestamp),
            zoneId = zoneIdUtc
        )
        val expectedWeekStartUtc = LocalDate.of(2026, 8, 10).atStartOfDay(zoneIdUtc).toInstant().toEpochMilli()
        assertEquals(expectedWeekStartUtc, historyUtc[0].date)
    }
}
