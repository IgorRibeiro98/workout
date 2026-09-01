package com.example

import com.example.domain.evolution.calculator.ConsistencyCalculator
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
    fun testConsecutiveSequence_threeDays() {
        val day1 = LocalDate.of(2026, 8, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day2 = LocalDate.of(2026, 8, 2).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day3 = LocalDate.of(2026, 8, 3).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val summary = ConsistencyCalculator.calculateConsistencySummary(
            timestamps = listOf(day1, day2, day3),
            zoneId = zoneId
        )

        assertEquals(3, summary.totalSessions)
        assertEquals(3, summary.currentStreak)
        assertEquals(3, summary.longestStreak)
    }

    @Test
    fun testSequenceBreak_threeDaysBreakOneTrainOne() {
        val day1 = LocalDate.of(2026, 8, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day2 = LocalDate.of(2026, 8, 2).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val day3 = LocalDate.of(2026, 8, 3).atStartOfDay(zoneId).toInstant().toEpochMilli()
        // Day 4 missed
        val day5 = LocalDate.of(2026, 8, 5).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val summary = ConsistencyCalculator.calculateConsistencySummary(
            timestamps = listOf(day1, day2, day3, day5),
            zoneId = zoneId
        )

        assertEquals(4, summary.totalSessions)
        assertEquals(1, summary.currentStreak)
        assertEquals(3, summary.longestStreak)
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
