package com.example.data.ai

import com.example.data.datastore.SettingsManager
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.WorkoutDao
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.ai.AiCoachContextBuilder
import com.example.domain.ai.AiCoachContextProjector
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiCoachContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * Lê as autoridades canônicas do Spark e entrega o contexto do Coach.
 *
 * Todo o IO fica aqui; o recorte e a preservação de identidade ficam em
 * [AiCoachContextProjector]. Este builder só lê — nenhuma escrita em Room ou DataStore.
 *
 * "Treino em foco" é resolvido sem duplicar a rotação de treinos que o Hoje já possui:
 * 1. a sessão ativa, quando existe (o treino em execução é o treino em foco);
 * 2. senão, o template fixado pelo usuário (`overrideTemplateId`);
 * 3. senão, nenhum — o contexto vai sem `currentWorkout` e a análise se apoia no histórico.
 */
class WorkoutAiCoachContextBuilder(
    private val workoutDao: WorkoutDao,
    private val settingsManager: SettingsManager,
    private val bodyMeasurementRepository: BodyMeasurementRepository
) : AiCoachContextBuilder {

    override suspend fun build(): AiCoachContext {
        val exercisesById = workoutDao.getAllExercisesSync().associateBy { it.id }

        val templateId = resolveWorkoutInFocusId()
        val template = templateId?.let { workoutDao.getTemplateById(it) }
        val templateExercises = template
            ?.let { workoutDao.getTemplateExercisesWithDetails(it.id).map { row -> row.templateExercise } }
            ?: emptyList()

        val recentSessions = workoutDao.getAllCompletedSessionsWithDetails()
            .take(AiModelConfig.RECENT_SESSIONS_LIMIT)

        val relatedExerciseRowIds = buildSet {
            templateExercises.forEach { add(it.exerciseId) }
            recentSessions.forEach { summary ->
                summary.exercises.forEach { executed ->
                    val rowId = executed.exerciseSession.actualExerciseId
                        ?: executed.exerciseSession.plannedExerciseId
                    if (rowId != null) add(rowId)
                }
            }
        }

        val personalRecords = mutableMapOf<Long, PersonalRecordEntity>()
        for (rowId in relatedExerciseRowIds) {
            val record = workoutDao.getHighestPR(rowId, PRType.MAX_WEIGHT.name) ?: continue
            personalRecords[rowId] = record
        }

        return AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = template?.name,
            templateExercises = templateExercises,
            recentSessions = recentSessions,
            personalRecordsByExerciseId = personalRecords,
            weeklyGoal = settingsManager.weeklyGoalFlow.first(),
            bodyWeightKg = bodyMeasurementRepository.latestMeasurement.firstOrNull()?.weightKg
        )
    }

    private suspend fun resolveWorkoutInFocusId(): Long? {
        workoutDao.getActiveSession()?.templateId?.let { return it }
        return settingsManager.overrideTemplateIdFlow.first()
    }
}
