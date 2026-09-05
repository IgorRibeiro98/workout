package com.example.data.ai

import com.example.data.local.ExerciseEntity
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.WorkoutDao
import com.example.domain.ai.AiCoachContextProjector
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.AiDataQualityPolicy
import com.example.domain.ai.AiWorkoutAdaptationContextBuilder
import com.example.domain.ai.AiWorkoutAdaptationSource
import com.example.domain.ai.ExerciseCandidateBuilder
import com.example.domain.ai.WorkoutTemplateRevision
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.model.WorkoutGoal
import com.example.domain.engine.MuscleGroup
import com.example.domain.engine.MuscleVisualResolver

/**
 * Lê o treino, o histórico real e o catálogo, e entrega o contexto da adaptação.
 *
 * Todo o IO fica aqui. O recorte reaproveita as autoridades que já existem:
 *
 * - histórico: [AiCoachContextProjector.projectExerciseHistory] — a mesma política da análise
 *   (até [AiModelConfig.HISTORY_PER_EXERCISE_LIMIT] execuções por exercício, dentro das
 *   [AiModelConfig.HISTORY_SCAN_SESSIONS] sessões concluídas mais recentes);
 * - PRs: [AiCoachContextProjector.projectPersonalRecords], a partir de `personal_records`;
 * - substitutos: [ExerciseCandidateBuilder], o mesmo filtro determinístico da geração.
 *
 * Este builder só lê — nenhuma escrita em Room ou DataStore.
 */
class WorkoutAiAdaptationContextBuilder(
    private val workoutDao: WorkoutDao
) : AiWorkoutAdaptationContextBuilder {

    override suspend fun build(templateId: Long): AiWorkoutAdaptationSource? {
        val template = workoutDao.getTemplateById(templateId) ?: return null
        val rows = workoutDao.getTemplateExercisesWithDetails(templateId).sortedBy { it.templateExercise.sortOrder }
        if (rows.isEmpty()) return null

        val exercisesById = workoutDao.getAllExercisesSync().associateBy { it.id }
        val plannedExercises = rows.map { row ->
            AiPlannedExerciseContext(
                exerciseId = AiCoachContextProjector.exerciseIdOf(row.exercise),
                name = row.exercise.name,
                targetSets = row.templateExercise.targetSets,
                minReps = row.templateExercise.minReps,
                maxReps = row.templateExercise.maxReps,
                plannedWeightKg = row.templateExercise.plannedWeight,
                restSeconds = row.templateExercise.restDurationSeconds
            )
        }

        // Só sessões COMPLETED: planejada, em andamento, pausada ou cancelada não é desempenho.
        val completedSessions = workoutDao.getAllCompletedSessionsWithDetails()
            .take(AiModelConfig.HISTORY_SCAN_SESSIONS)
        val projection = AiCoachContextProjector.projectExerciseHistory(
            exercisesById = exercisesById,
            exerciseRowIds = rows.map { it.exercise.id }.distinct(),
            completedSessions = completedSessions
        )

        val maxDataQuality = AiDataQualityPolicy.ceilingFor(projection.sessionsAnalyzed)
        val idsInContext = plannedExercises.mapTo(mutableSetOf()) { it.exerciseId }

        return AiWorkoutAdaptationSource(
            templateId = templateId,
            revision = WorkoutTemplateRevision.of(template, rows.map { it.templateExercise }),
            context = AiWorkoutAdaptationContext(
                templateName = template.name,
                template = AiWorkoutContext(templateName = template.name, exercises = plannedExercises),
                exerciseHistory = projection.history,
                personalRecords = AiCoachContextProjector.projectPersonalRecords(
                    exercisesById = exercisesById,
                    personalRecordsByExerciseId = loadPersonalRecords(rows.map { it.exercise.id }),
                    idsInContext = idsInContext
                ),
                replacementCandidates = buildReplacementCandidates(rows.map { it.exercise }, idsInContext),
                allowedChangeTypes = allowedChangeTypes(maxDataQuality).map { it.name },
                evidence = AiEvidenceContext(
                    sessionsAnalyzed = projection.sessionsAnalyzed,
                    exercisesWithHistory = projection.history.count { it.executions.isNotEmpty() },
                    maxDataQuality = maxDataQuality
                )
            )
        )
    }

    /**
     * Quais tipos de mudança o app aceita, dado o histórico que conseguiu reunir.
     *
     * Sem nenhuma sessão concluída não há progressão a inferir: carga, repetições e séries nem
     * chegam a ser oferecidas ao modelo. O que resta é estrutural — descanso e substituição — e
     * a proposta continua carimbada como de baixa evidência pelo `dataQuality`.
     */
    private fun allowedChangeTypes(maxDataQuality: AiDataQualityLevel): List<WorkoutAdaptationType> =
        if (maxDataQuality == AiDataQualityLevel.INSUFFICIENT) {
            WorkoutAdaptationType.entries.filterNot { it.requiresPerformanceEvidence }
        } else {
            WorkoutAdaptationType.entries
        }

    /**
     * Substitutos possíveis, grupo muscular a grupo muscular do treino.
     *
     * Reaproveita o filtro da geração: por grupo, no máximo
     * [AiModelConfig.MAX_CANDIDATES_PER_MUSCLE_GROUP] exercícios; no total, no máximo
     * [AiModelConfig.MAX_CANDIDATE_EXERCISES]. Os exercícios que já estão no treino ficam de fora
     * — substituir um exercício por outro que já está lá duplicaria o treino.
     */
    private suspend fun buildReplacementCandidates(
        templateExercises: List<ExerciseEntity>,
        idsInTemplate: Set<String>
    ): List<AiCandidateExerciseContext> {
        val catalog = workoutDao.getAllExercisesList()
        val groups = templateExercises
            .map { MuscleVisualResolver.resolveGroup(it.primaryMuscle) }
            .distinct()

        val candidates = mutableListOf<AiCandidateExerciseContext>()
        val seen = mutableSetOf<String>()
        for (group in groups) {
            if (candidates.size >= AiModelConfig.MAX_CANDIDATE_EXERCISES) break
            val perGroup = ExerciseCandidateBuilder.build(catalog, preferencesFor(group, idsInTemplate))
            for (candidate in perGroup) {
                if (candidates.size >= AiModelConfig.MAX_CANDIDATE_EXERCISES) break
                if (seen.add(candidate.exerciseId)) candidates += candidate
            }
        }
        return candidates
    }

    /**
     * O filtro de candidatos para um grupo muscular.
     *
     * Objetivo e duração não influenciam [ExerciseCandidateBuilder] — ele filtra por foco,
     * equipamento e exclusões. Aqui não há restrição de equipamento porque o usuário não pediu
     * nenhuma: a adaptação parte do treino que ele já monta e executa.
     */
    private fun preferencesFor(group: MuscleGroup, idsInTemplate: Set<String>) =
        WorkoutGenerationPreferences(
            goal = WorkoutGoal.GENERAL_FITNESS,
            durationMinutes = WorkoutGenerationPreferences.DEFAULT_DURATION_MINUTES,
            focusMuscleGroups = listOf(group),
            availableEquipment = setOf(EquipmentAvailability.FULL_GYM),
            excludedExerciseIds = idsInTemplate
        )

    /** PR é lido da autoridade persistida (`personal_records`), nunca reinferido das séries. */
    private suspend fun loadPersonalRecords(exerciseRowIds: List<Long>): Map<Long, PersonalRecordEntity> {
        val records = mutableMapOf<Long, PersonalRecordEntity>()
        for (rowId in exerciseRowIds.distinct().take(AiModelConfig.MAX_EXERCISES_IN_CONTEXT)) {
            val record = workoutDao.getHighestPR(rowId, PRType.MAX_WEIGHT.name) ?: continue
            records[rowId] = record
        }
        return records
    }
}
