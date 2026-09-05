package com.example.domain.ai

import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiCoachResponseDataQuality
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiWorkoutAdaptationChangeResponse
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutAdaptationResponse
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.engine.MuscleGroup

/** Fixtures compartilhadas pelos testes de adaptação de treino. */
object WorkoutAdaptationTestData {

    const val TEMPLATE_ID: Long = 7L
    const val REVISION: String = "7|Peito + Tríceps|1:1:0:4:8-12:90:60.0;"

    fun plannedExercise(
        exerciseId: String = "supino-reto-barra",
        name: String = "Supino reto com barra",
        targetSets: Int? = 4,
        minReps: Int? = 8,
        maxReps: Int? = 12,
        plannedWeightKg: Float? = 60f,
        restSeconds: Int? = 90
    ) = AiPlannedExerciseContext(
        exerciseId = exerciseId,
        name = name,
        targetSets = targetSets,
        minReps = minReps,
        maxReps = maxReps,
        plannedWeightKg = plannedWeightKg,
        restSeconds = restSeconds
    )

    fun candidate(
        exerciseId: String = "supino-reto-halteres",
        name: String = "Supino reto com halteres"
    ) = AiCandidateExerciseContext(
        exerciseId = exerciseId,
        name = name,
        muscleGroup = MuscleGroup.CHEST.displayName,
        equipment = "Halteres"
    )

    fun context(
        exercises: List<AiPlannedExerciseContext> = listOf(plannedExercise()),
        replacementCandidates: List<AiCandidateExerciseContext> = listOf(candidate()),
        allowedChangeTypes: List<WorkoutAdaptationType> = WorkoutAdaptationType.entries,
        maxDataQuality: AiDataQualityLevel = AiDataQualityLevel.GOOD,
        sessionsAnalyzed: Int = 4
    ) = AiWorkoutAdaptationContext(
        templateName = "Peito + Tríceps",
        template = AiWorkoutContext(templateName = "Peito + Tríceps", exercises = exercises),
        replacementCandidates = replacementCandidates,
        allowedChangeTypes = allowedChangeTypes.map { it.name },
        evidence = AiEvidenceContext(
            sessionsAnalyzed = sessionsAnalyzed,
            exercisesWithHistory = exercises.size,
            maxDataQuality = maxDataQuality
        )
    )

    fun loadChange(
        exerciseId: String = "supino-reto-barra",
        currentWeightKg: Double? = 60.0,
        suggestedWeightKg: Double? = 62.5,
        reason: String = "As últimas sessões foram concluídas com a carga planejada.",
        evidence: String = "60 kg em 3 sessões concluídas",
        confidence: Double = 0.84
    ) = AiWorkoutAdaptationChangeResponse(
        type = WorkoutAdaptationType.ADJUST_LOAD.name,
        exerciseId = exerciseId,
        currentWeightKg = currentWeightKg,
        suggestedWeightKg = suggestedWeightKg,
        reason = reason,
        evidence = evidence,
        confidence = confidence
    )

    fun repsChange(
        exerciseId: String = "supino-reto-barra",
        currentMinReps: Int? = 8,
        currentMaxReps: Int? = 12,
        suggestedMinReps: Int? = 10,
        suggestedMaxReps: Int? = 14
    ) = AiWorkoutAdaptationChangeResponse(
        type = WorkoutAdaptationType.ADJUST_REPS.name,
        exerciseId = exerciseId,
        currentMinReps = currentMinReps,
        currentMaxReps = currentMaxReps,
        suggestedMinReps = suggestedMinReps,
        suggestedMaxReps = suggestedMaxReps,
        reason = "A carga ficou estável e a faixa atual foi atingida.",
        evidence = "12 repetições na última execução registrada",
        confidence = 0.7
    )

    fun setsChange(
        exerciseId: String = "supino-reto-barra",
        currentSets: Int? = 4,
        suggestedSets: Int? = 5
    ) = AiWorkoutAdaptationChangeResponse(
        type = WorkoutAdaptationType.ADJUST_SETS.name,
        exerciseId = exerciseId,
        currentSets = currentSets,
        suggestedSets = suggestedSets,
        reason = "Todas as séries previstas foram concluídas nas últimas sessões.",
        evidence = "4 de 4 séries concluídas em 3 sessões",
        confidence = 0.6
    )

    fun restChange(
        exerciseId: String = "supino-reto-barra",
        currentRestSeconds: Int? = 90,
        suggestedRestSeconds: Int? = 120
    ) = AiWorkoutAdaptationChangeResponse(
        type = WorkoutAdaptationType.ADJUST_REST.name,
        exerciseId = exerciseId,
        currentRestSeconds = currentRestSeconds,
        suggestedRestSeconds = suggestedRestSeconds,
        reason = "A carga subiu e o descanso atual é curto para o movimento principal.",
        evidence = "Carga de 60 kg para 70 kg entre as sessões registradas",
        confidence = 0.55
    )

    fun replacementChange(
        exerciseId: String = "supino-reto-barra",
        replacementExerciseId: String? = "supino-reto-halteres"
    ) = AiWorkoutAdaptationChangeResponse(
        type = WorkoutAdaptationType.REPLACE_EXERCISE.name,
        exerciseId = exerciseId,
        replacementExerciseId = replacementExerciseId,
        reason = "Alternativa com o mesmo padrão de movimento.",
        evidence = "Mesmo grupo muscular do exercício atual",
        confidence = 0.5
    )

    fun response(
        summary: String = "Seu treino está evoluindo de forma consistente.",
        changes: List<AiWorkoutAdaptationChangeResponse> = listOf(loadChange()),
        dataQuality: AiCoachResponseDataQuality? = AiCoachResponseDataQuality(
            level = AiDataQualityLevel.GOOD.name,
            description = "Baseado nas sessões concluídas enviadas."
        )
    ) = AiWorkoutAdaptationResponse(
        summary = summary,
        changes = changes,
        dataQuality = dataQuality
    )
}
