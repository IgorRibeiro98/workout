package com.example.data.repository

import com.example.data.datastore.SettingsManager
import com.example.data.local.WeeklyGoalDao
import com.example.data.local.WeeklyGoalHistoryEntity
import com.example.data.local.WorkoutDao
import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class ConsistencyRepositoryImpl(
    private val workoutDao: WorkoutDao,
    private val weeklyGoalDao: WeeklyGoalDao? = null,
    private val settingsManager: SettingsManager? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ConsistencyRepository {

    override suspend fun getConsistencySummary(): WorkoutConsistencySummary {
        val timestamps = workoutDao.getCompletedSessionTimestamps()
        val snapshots = getGoalSnapshots()
        val defaultGoal = settingsManager?.weeklyGoalFlow?.first() ?: 3
        return ConsistencyCalculator.calculateConsistencySummary(
            timestamps = timestamps,
            goalSnapshots = snapshots,
            defaultGoal = defaultGoal,
            zoneId = zoneId
        )
    }

    override suspend fun getFrequencyHistory(): List<WorkoutFrequencyPoint> {
        val timestamps = workoutDao.getCompletedSessionTimestamps()
        return ConsistencyCalculator.calculateFrequencyHistory(timestamps, zoneId)
    }

    override suspend fun getConsistencyProgress(): ConsistencyProgress {
        val consistencies = getWeeklyConsistencies()
        return ConsistencyCalculator.calculateProgress(consistencies, LocalDate.now(zoneId))
    }

    override suspend fun getWeeklyConsistencies(): List<WeeklyConsistency> {
        val timestamps = workoutDao.getCompletedSessionTimestamps()
        val snapshots = getGoalSnapshots()
        val defaultGoal = settingsManager?.weeklyGoalFlow?.first() ?: 3
        return ConsistencyCalculator.calculateWeeklyConsistencies(
            timestamps = timestamps,
            goalSnapshots = snapshots,
            defaultGoal = defaultGoal,
            referenceDate = LocalDate.now(zoneId),
            zoneId = zoneId
        )
    }

    override suspend fun getGoalSnapshots(): List<WeeklyGoalSnapshot> {
        val entities = weeklyGoalDao?.getAllGoals() ?: emptyList()
        return entities.map {
            WeeklyGoalSnapshot(
                effectiveFromWeek = it.effectiveFromWeekStartEpochDay,
                goal = it.goal
            )
        }
    }

    override suspend fun setWeeklyGoal(newGoal: Int) {
        val today = LocalDate.now(zoneId)
        val currentMonday = today.with(DayOfWeek.MONDAY)
        val nextMonday = currentMonday.plusWeeks(1)
        val currentMondayEpochDay = currentMonday.toEpochDay()
        val nextMondayEpochDay = nextMonday.toEpochDay()

        weeklyGoalDao?.let { dao ->
            // If current week doesn't have an explicit record, preserve current goal
            val currentGoalEntity = dao.getGoalForWeek(currentMondayEpochDay)
            val currentGoalValue = currentGoalEntity?.goal ?: settingsManager?.weeklyGoalFlow?.first() ?: newGoal
            dao.insertGoal(
                WeeklyGoalHistoryEntity(
                    effectiveFromWeekStartEpochDay = currentMondayEpochDay,
                    goal = currentGoalValue
                )
            )

            // Set new goal for next week
            dao.insertGoal(
                WeeklyGoalHistoryEntity(
                    effectiveFromWeekStartEpochDay = nextMondayEpochDay,
                    goal = newGoal
                )
            )
        }

        settingsManager?.setWeeklyGoal(newGoal)
    }

    override fun getConsistencySummaryFlow(): Flow<WorkoutConsistencySummary> {
        val goalFlow = weeklyGoalDao?.getAllGoalsFlow()?.map { list ->
            list.map { WeeklyGoalSnapshot(it.effectiveFromWeekStartEpochDay, it.goal) }
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())

        val defaultGoalFlow = settingsManager?.weeklyGoalFlow ?: kotlinx.coroutines.flow.flowOf(3)

        return combine(
            workoutDao.getAllCompletedSessionsWithDetailsFlow(),
            goalFlow,
            defaultGoalFlow
        ) { sessions, snapshots, defaultGoal ->
            val timestamps = sessions.map { it.session.startedAt }
            ConsistencyCalculator.calculateConsistencySummary(
                timestamps = timestamps,
                goalSnapshots = snapshots,
                defaultGoal = defaultGoal,
                zoneId = zoneId
            )
        }
    }

    override fun getFrequencyHistoryFlow(): Flow<List<WorkoutFrequencyPoint>> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            ConsistencyCalculator.calculateFrequencyHistory(sessions.map { it.session.startedAt }, zoneId)
        }
    }

    override fun getWorkoutTimestampsFlow(): Flow<List<Long>> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            sessions.map { it.session.startedAt }
        }
    }

    override fun getConsistencyProgressFlow(): Flow<ConsistencyProgress> {
        return getWeeklyConsistenciesFlow().map { consistencies ->
            ConsistencyCalculator.calculateProgress(consistencies, LocalDate.now(zoneId))
        }
    }

    override fun getWeeklyConsistenciesFlow(): Flow<List<WeeklyConsistency>> {
        val goalFlow = weeklyGoalDao?.getAllGoalsFlow()?.map { list ->
            list.map { WeeklyGoalSnapshot(it.effectiveFromWeekStartEpochDay, it.goal) }
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())

        val defaultGoalFlow = settingsManager?.weeklyGoalFlow ?: kotlinx.coroutines.flow.flowOf(3)

        return combine(
            workoutDao.getAllCompletedSessionsWithDetailsFlow(),
            goalFlow,
            defaultGoalFlow
        ) { sessions, snapshots, defaultGoal ->
            val timestamps = sessions.map { it.session.startedAt }
            ConsistencyCalculator.calculateWeeklyConsistencies(
                timestamps = timestamps,
                goalSnapshots = snapshots,
                defaultGoal = defaultGoal,
                referenceDate = LocalDate.now(zoneId),
                zoneId = zoneId
            )
        }
    }

    override fun getGoalSnapshotsFlow(): Flow<List<WeeklyGoalSnapshot>> {
        return weeklyGoalDao?.getAllGoalsFlow()?.map { list ->
            list.map { WeeklyGoalSnapshot(it.effectiveFromWeekStartEpochDay, it.goal) }
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
}
