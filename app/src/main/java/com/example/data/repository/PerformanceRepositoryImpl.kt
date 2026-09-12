package com.example.data.repository

import com.example.data.local.WorkoutDao
import com.example.domain.evolution.calculator.PerformanceCalculator
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.domain.performance.calculator.VolumeCalculator
import com.example.domain.performance.model.volume.VolumeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PerformanceRepositoryImpl(
    private val workoutDao: WorkoutDao
) : PerformanceRepository {

    /**
     * O histórico completo, sem reemitir o que não mudou.
     *
     * Seis consultas observáveis deste repositório partem daqui, e o Room reemite a consulta a cada
     * escrita em `workout_sessions`, `exercise_sessions` ou `set_logs`. Durante um treino, isso é
     * **uma vez por série concluída** — com o resultado idêntico, já que a sessão em andamento não
     * é `COMPLETED`. Comparar a lista custa muito menos do que recalcular volume, recordes e
     * evolução de todos os exercícios seis vezes.
     */
    private val completedHistory =
        workoutDao.getAllCompletedSessionsWithDetailsFlow().distinctUntilChanged()

    override suspend fun getPerformanceSummary(): WorkoutPerformanceSummary {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateWorkoutPerformanceSummary(sessions)
    }

    override suspend fun getVolumeSummary(): VolumeSummary {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return VolumeCalculator.calculateVolumeSummary(sessions)
    }

    override suspend fun getExerciseEvolution(exerciseId: String): ExercisePerformanceEvolution? {
        val all = getAllExercisesEvolution()
        return all.find { it.exerciseId == exerciseId }
    }

    override suspend fun getAllExercisesEvolution(): List<ExercisePerformanceEvolution> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateExerciseEvolutions(sessions)
    }

    override suspend fun getPersonalRecords(): List<PersonalRecord> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculatePersonalRecords(sessions)
    }

    override suspend fun getVolumeHistory(): List<VolumePoint> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateVolumeHistory(sessions)
    }

    override suspend fun getExerciseStrengthHistory(exerciseId: String): List<StrengthPoint> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateExerciseStrengthHistory(sessions, exerciseId)
    }

    override fun getPerformanceSummaryFlow(): Flow<WorkoutPerformanceSummary> {
        return completedHistory.map { sessions ->
            PerformanceCalculator.calculateWorkoutPerformanceSummary(sessions)
        }
    }

    override fun getVolumeSummaryFlow(): Flow<VolumeSummary> {
        return completedHistory.map { sessions ->
            VolumeCalculator.calculateVolumeSummary(sessions)
        }
    }

    override fun getAllExercisesEvolutionFlow(): Flow<List<ExercisePerformanceEvolution>> {
        return completedHistory.map { sessions ->
            PerformanceCalculator.calculateExerciseEvolutions(sessions)
        }
    }

    override fun getPersonalRecordsFlow(): Flow<List<PersonalRecord>> {
        return completedHistory.map { sessions ->
            PerformanceCalculator.calculatePersonalRecords(sessions)
        }
    }

    override fun getVolumeHistoryFlow(): Flow<List<VolumePoint>> {
        return completedHistory.map { sessions ->
            PerformanceCalculator.calculateVolumeHistory(sessions)
        }
    }

    override fun getExerciseStrengthHistoryFlow(exerciseId: String): Flow<List<StrengthPoint>> {
        return completedHistory.map { sessions ->
            PerformanceCalculator.calculateExerciseStrengthHistory(sessions, exerciseId)
        }
    }
}
