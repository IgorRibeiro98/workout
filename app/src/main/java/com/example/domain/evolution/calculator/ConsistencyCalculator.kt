package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

object ConsistencyCalculator {

    /**
     * Builds weekly consistencies from workout timestamps and historical goal snapshots.
     */
    fun calculateWeeklyConsistencies(
        timestamps: List<Long>,
        goalSnapshots: List<WeeklyGoalSnapshot> = emptyList(),
        defaultGoal: Int = 3,
        referenceDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<WeeklyConsistency> {
        val currentMonday = referenceDate.with(DayOfWeek.MONDAY)
        val sortedGoalSnapshots = goalSnapshots.sortedBy { it.effectiveFromWeek }

        fun resolveGoal(weekStartEpochDay: Long): Int {
            val snapshot = sortedGoalSnapshots.lastOrNull { it.effectiveFromWeek <= weekStartEpochDay }
            return snapshot?.goal ?: defaultGoal
        }

        if (timestamps.isEmpty()) {
            val epochDay = currentMonday.toEpochDay()
            return listOf(
                WeeklyConsistency(
                    weekStartEpochDay = epochDay,
                    goal = resolveGoal(epochDay),
                    completedWorkouts = 0,
                    status = WeeklyConsistencyStatus.IN_PROGRESS
                )
            )
        }

        val workoutsByWeek = timestamps
            .map { ts ->
                Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate().with(DayOfWeek.MONDAY)
            }
            .groupingBy { it }
            .eachCount()

        val earliestWorkoutMonday = workoutsByWeek.keys.minOrNull() ?: currentMonday
        val startMonday = if (earliestWorkoutMonday.isBefore(currentMonday)) earliestWorkoutMonday else currentMonday

        val result = mutableListOf<WeeklyConsistency>()
        var week = startMonday
        while (!week.isAfter(currentMonday)) {
            val epochDay = week.toEpochDay()
            val count = workoutsByWeek[week] ?: 0
            val goal = resolveGoal(epochDay)
            val isCurrentWeek = (week == currentMonday)

            val status = if (isCurrentWeek) {
                if (count >= goal) WeeklyConsistencyStatus.COMPLETED else WeeklyConsistencyStatus.IN_PROGRESS
            } else {
                if (count >= goal) WeeklyConsistencyStatus.COMPLETED else WeeklyConsistencyStatus.MISSED
            }

            result.add(
                WeeklyConsistency(
                    weekStartEpochDay = epochDay,
                    goal = goal,
                    completedWorkouts = count,
                    status = status
                )
            )
            week = week.plusWeeks(1)
        }

        return result
    }

    /**
     * Calculates the consistency progress (current and longest streak in weeks).
     */
    fun calculateProgress(
        weeklyConsistencies: List<WeeklyConsistency>,
        referenceDate: LocalDate = LocalDate.now()
    ): ConsistencyProgress {
        if (weeklyConsistencies.isEmpty()) {
            return ConsistencyProgress(
                currentStreakWeeks = 0,
                longestStreakWeeks = 0,
                currentWeekCompleted = 0,
                currentWeekGoal = 3,
                currentWeekStatus = WeeklyConsistencyStatus.IN_PROGRESS
            )
        }

        val currentMonday = referenceDate.with(DayOfWeek.MONDAY)
        val currentWeek = weeklyConsistencies.firstOrNull { it.weekStartEpochDay == currentMonday.toEpochDay() }
            ?: weeklyConsistencies.last()

        val pastWeeks = weeklyConsistencies.filter { it.weekStartEpochDay < currentWeek.weekStartEpochDay }

        // Regra 8 — Primeira semana parcial não penaliza usuário se não atingiu a meta
        val isFirstWeekPartialMissed = pastWeeks.isNotEmpty() &&
                pastWeeks.first().status == WeeklyConsistencyStatus.MISSED

        // Calculate Current Streak
        var currentStreak = 0
        if (currentWeek.status == WeeklyConsistencyStatus.COMPLETED) {
            currentStreak = 1
            for (i in (pastWeeks.size - 1) downTo 0) {
                val past = pastWeeks[i]
                if (past.status == WeeklyConsistencyStatus.COMPLETED) {
                    currentStreak++
                } else if (i == 0 && isFirstWeekPartialMissed) {
                    // First partial week missed does not break streak
                    break
                } else {
                    break
                }
            }
        } else {
            // Current week is IN_PROGRESS: streak is from past closed weeks
            for (i in (pastWeeks.size - 1) downTo 0) {
                val past = pastWeeks[i]
                if (past.status == WeeklyConsistencyStatus.COMPLETED) {
                    currentStreak++
                } else if (i == 0 && isFirstWeekPartialMissed) {
                    break
                } else {
                    break
                }
            }
        }

        // Calculate Longest Streak
        var longestStreak = 0
        var currentRun = 0

        // Evaluate past weeks (ignoring first partial if missed)
        val weeksToEvaluate = if (isFirstWeekPartialMissed) pastWeeks.drop(1) else pastWeeks
        for (w in weeksToEvaluate) {
            if (w.status == WeeklyConsistencyStatus.COMPLETED) {
                currentRun++
                if (currentRun > longestStreak) {
                    longestStreak = currentRun
                }
            } else {
                currentRun = 0
            }
        }

        // Include current week if COMPLETED
        if (currentWeek.status == WeeklyConsistencyStatus.COMPLETED) {
            currentRun++
            if (currentRun > longestStreak) {
                longestStreak = currentRun
            }
        }

        // Ensure longest streak is at least current streak
        if (currentStreak > longestStreak) {
            longestStreak = currentStreak
        }

        return ConsistencyProgress(
            currentStreakWeeks = currentStreak,
            longestStreakWeeks = longestStreak,
            currentWeekCompleted = currentWeek.completedWorkouts,
            currentWeekGoal = currentWeek.goal,
            currentWeekStatus = currentWeek.status
        )
    }

    /**
     * Calculates consistency summary for domain consistency model using weekly streaks.
     */
    fun calculateConsistencySummary(
        timestamps: List<Long>,
        goalSnapshots: List<WeeklyGoalSnapshot> = emptyList(),
        defaultGoal: Int = 3,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): WorkoutConsistencySummary {
        if (timestamps.isEmpty()) {
            return WorkoutConsistencySummary(
                totalSessions = 0,
                currentStreak = 0,
                longestStreak = 0,
                averageSessionsPerWeek = 0f,
                lastWorkoutDate = null
            )
        }

        val referenceDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val weeklyConsistencies = calculateWeeklyConsistencies(
            timestamps = timestamps,
            goalSnapshots = goalSnapshots,
            defaultGoal = defaultGoal,
            referenceDate = referenceDate,
            zoneId = zoneId
        )

        val progress = calculateProgress(weeklyConsistencies, referenceDate)

        val localDates = timestamps.map {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }
        val sortedDistinctDays = localDates.distinct().sorted()
        val totalSessions = timestamps.size
        val firstDate = sortedDistinctDays.first()
        val lastDate = sortedDistinctDays.last()
        val daysSpan = ChronoUnit.DAYS.between(firstDate, lastDate) + 1
        val weeksSpan = max(1.0f, daysSpan / 7.0f)
        val averageSessionsPerWeek = totalSessions / weeksSpan
        val roundedAvg = kotlin.math.round(averageSessionsPerWeek * 10f) / 10f

        return WorkoutConsistencySummary(
            totalSessions = totalSessions,
            currentStreak = progress.currentStreakWeeks,
            longestStreak = progress.longestStreakWeeks,
            averageSessionsPerWeek = roundedAvg,
            lastWorkoutDate = timestamps.maxOrNull()
        )
    }

    /**
     * Calculates weekly workout frequency history points.
     */
    fun calculateFrequencyHistory(
        timestamps: List<Long>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<WorkoutFrequencyPoint> {
        if (timestamps.isEmpty()) return emptyList()

        val datesWithWeekStart = timestamps.map { ts ->
            val date = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
            val weekStart = date.with(DayOfWeek.MONDAY)
            weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli() to ts
        }

        val grouped = datesWithWeekStart.groupBy { it.first }
        return grouped.map { (weekStartMillis, items) ->
            WorkoutFrequencyPoint(
                date = weekStartMillis,
                sessions = items.size
            )
        }.sortedBy { it.date }
    }

    /**
     * Calculates legacy consistency metrics from a list of local dates.
     */
    fun calculateFromDates(
        dates: List<LocalDate>,
        referenceDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ConsistencyMetrics {
        val timestamps = dates.map { it.atStartOfDay(zoneId).toInstant().toEpochMilli() }
        val nowMillis = referenceDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return calculate(timestamps, zoneId, nowMillis)
    }

    /**
     * Calculates legacy consistency metrics.
     */
    fun calculate(
        timestamps: List<Long>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): ConsistencyMetrics {
        val summary = calculateConsistencySummary(
            timestamps = timestamps,
            zoneId = zoneId,
            nowMillis = nowMillis
        )

        val referenceDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val localDates = timestamps.map {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }
        val distinctDays = localDates.distinct()
        val monthlySessions = localDates.count { it.month == referenceDate.month && it.year == referenceDate.year }

        return ConsistencyMetrics(
            trainingDays = distinctDays.size,
            currentStreak = summary.currentStreak,
            longestStreak = summary.longestStreak,
            monthlySessions = monthlySessions,
            averageSessionsPerWeek = summary.averageSessionsPerWeek
        )
    }
}
