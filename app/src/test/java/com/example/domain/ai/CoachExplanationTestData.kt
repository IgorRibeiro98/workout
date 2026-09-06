package com.example.domain.ai

import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.AiCoachExplanationResponse
import com.example.domain.ai.model.AiCoachObservation
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiExerciseExecutionContext
import com.example.domain.ai.model.AiExerciseHistoryContext
import com.example.domain.ai.model.AiProgressSnapshot
import com.example.domain.ai.model.AiRecommendation
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.GeneratedWorkoutDraftExercise
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.model.WorkoutGoal
import com.example.domain.engine.MuscleGroup

/** Fixtures compartilhadas pelos testes de explicação contextual (T14.4). */
object CoachExplanationTestData {

    const val ANALYSIS_REQUEST_ID: String = "analysis-1"

    fun recommendation(
        id: String = "${AiCoachAdvice.RECOMMENDATION_ID_PREFIX}:0",
        exerciseId: String? = "supino-reto-barra",
        evidence: String? = "60 kg nas últimas 4 sessões concluídas"
    ) = AiRecommendation(
        id = id,
        type = AiRecommendationType.REVIEW_LOAD,
        exerciseId = exerciseId,
        reason = "A carga permaneceu igual nas últimas sessões.",
        confidence = 0.8,
        evidence = evidence
    )

    fun observation(
        id: String = "${AiCoachAdvice.ATTENTION_POINT_ID_PREFIX}:0",
        exerciseId: String? = "supino-reto-barra"
    ) = AiCoachObservation(
        id = id,
        exerciseId = exerciseId,
        title = "Carga estável",
        description = "A carga registrada não mudou nas últimas sessões."
    )

    fun advice(
        requestId: String = ANALYSIS_REQUEST_ID,
        recommendations: List<AiRecommendation> = listOf(recommendation()),
        attentionPoints: List<AiCoachObservation> = listOf(observation()),
        level: AiDataQualityLevel = AiDataQualityLevel.GOOD,
        sessionsAnalyzed: Int = 4
    ) = AiCoachAdvice(
        requestId = requestId,
        summary = "Progressão estável.",
        positiveSignals = emptyList(),
        attentionPoints = attentionPoints,
        recommendations = recommendations,
        dataQuality = AiCoachDataQuality(level, "Baseada nas sessões concluídas."),
        sessionsAnalyzed = sessionsAnalyzed
    )

    fun generatedDraft(
        requestId: String = "generation-1",
        exercises: List<GeneratedWorkoutDraftExercise> = listOf(draftExercise())
    ) = GeneratedWorkoutDraft(
        requestId = requestId,
        name = "Peito e tríceps",
        explanation = "Movimentos compostos primeiro, isoladores depois.",
        exercises = exercises
    )

    fun draftExercise(
        exerciseId: String = "supino-reto-barra",
        name: String = "Supino reto com barra",
        sortOrder: Int = 0
    ) = GeneratedWorkoutDraftExercise(
        exerciseId = exerciseId,
        name = name,
        sortOrder = sortOrder,
        sets = 4,
        minReps = 8,
        maxReps = 12,
        restSeconds = 90,
        weightKg = null,
        reason = "Composto principal do grupo pedido."
    )

    fun preferences(
        focus: List<MuscleGroup> = listOf(MuscleGroup.CHEST),
        excluded: Set<String> = emptySet()
    ) = WorkoutGenerationPreferences(
        goal = WorkoutGoal.HYPERTROPHY,
        durationMinutes = 60,
        focusMuscleGroups = focus,
        excludedExerciseIds = excluded
    )

    fun history(
        exerciseId: String = "supino-reto-barra",
        weights: List<Float> = listOf(60f, 60f, 60f)
    ) = AiExerciseHistoryContext(
        exerciseId = exerciseId,
        name = "Supino reto com barra",
        sessionsAnalyzed = weights.size,
        executions = weights.map { weight ->
            AiExerciseExecutionContext(
                finishedAtEpochMs = null,
                completedSets = 4,
                maxWeightKg = weight,
                totalReps = 40
            )
        }
    )

    fun progress(
        level: Int = 5,
        streakWeeks: Int = 4,
        completedWorkouts: Int = 32
    ) = AiProgressSnapshot(
        level = level,
        totalXp = 4200,
        currentLevelXp = 200,
        xpForNextLevel = 1000,
        streakWeeks = streakWeeks,
        weeklyCompleted = 2,
        weeklyGoal = 3,
        completedWorkouts = completedWorkouts,
        unlockedAchievements = 7,
        totalAchievements = 20,
        personalRecordsCount = 11
    )

    fun explanationResponse(
        title: String = "Por que essa mudança?",
        explanation: String = "As últimas execuções concluídas atingiram o alvo com a mesma carga.",
        limitations: List<String> = emptyList(),
        referencedExerciseIds: List<String> = emptyList()
    ) = AiCoachExplanationResponse(
        title = title,
        explanation = explanation,
        limitations = limitations,
        referencedExerciseIds = referencedExerciseIds
    )
}
