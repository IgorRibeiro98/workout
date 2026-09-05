package com.example.data.ai

import com.example.data.datastore.SettingsManager
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.WorkoutDao
import com.example.domain.ai.AiCoachContextBuilder
import com.example.domain.ai.AiCoachContextProjector
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiCoachContext
import kotlinx.coroutines.flow.first

/**
 * Lê as autoridades canônicas do Spark e entrega o contexto da análise.
 *
 * Todo o IO fica aqui; o recorte e a preservação de identidade ficam em
 * [AiCoachContextProjector]. Este builder só lê — nenhuma escrita em Room ou DataStore.
 *
 * "Treino em foco" é resolvido sem duplicar a rotação de treinos que o Hoje já possui:
 * 1. a sessão ativa, quando existe (o treino em execução é o treino em foco);
 * 2. senão, o template fixado pelo usuário (`overrideTemplateId`);
 * 3. senão, nenhum — a análise se apoia no último treino concluído.
 *
 * Peso corporal e medidas não são lidos aqui: uma análise de carga e volume não depende deles,
 * e enviar dado corporal em toda chamada seria exposição sem finalidade.
 */
class WorkoutAiCoachContextBuilder(
    private val workoutDao: WorkoutDao,
    private val settingsManager: SettingsManager
) : AiCoachContextBuilder {

    override suspend fun build(): AiCoachContext {
        val exercisesById = workoutDao.getAllExercisesSync().associateBy { it.id }

        val templateId = resolveWorkoutInFocusId()
        val template = templateId?.let { workoutDao.getTemplateById(it) }
        val templateExercises = template
            ?.let { workoutDao.getTemplateExercisesWithDetails(it.id).map { row -> row.templateExercise } }
            ?: emptyList()

        // Só sessões COMPLETED: planejada, em andamento, pausada ou cancelada não é desempenho.
        val completedSessions = workoutDao.getAllCompletedSessionsWithDetails()
            .take(AiModelConfig.HISTORY_SCAN_SESSIONS)

        return AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = template?.name,
            templateExercises = templateExercises,
            completedSessions = completedSessions,
            personalRecordsByExerciseId = loadPersonalRecords(templateExercises.map { it.exerciseId }),
            weeklyGoal = settingsManager.weeklyGoalFlow.first()
        )
    }

    /**
     * PR é lido da autoridade persistida (`personal_records`), nunca reinferido das séries.
     *
     * Sem treino em foco a busca segue os exercícios do último treino concluído — os mesmos que
     * o projetor considera relevantes.
     */
    private suspend fun loadPersonalRecords(plannedExerciseIds: List<Long>): Map<Long, PersonalRecordEntity> {
        val rowIds = plannedExerciseIds.ifEmpty {
            workoutDao.getLastCompletedSession()
                ?.let { session -> workoutDao.getExerciseSessionsForSession(session.id) }
                ?.mapNotNull { it.actualExerciseId ?: it.plannedExerciseId }
                ?: emptyList()
        }.distinct().take(AiModelConfig.MAX_EXERCISES_IN_CONTEXT)

        val records = mutableMapOf<Long, PersonalRecordEntity>()
        for (rowId in rowIds) {
            val record = workoutDao.getHighestPR(rowId, PRType.MAX_WEIGHT.name) ?: continue
            records[rowId] = record
        }
        return records
    }

    private suspend fun resolveWorkoutInFocusId(): Long? {
        workoutDao.getActiveSession()?.templateId?.let { return it }
        return settingsManager.overrideTemplateIdFlow.first()
    }
}
