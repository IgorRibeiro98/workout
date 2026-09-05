package com.example.domain.ai

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseDataQuality
import com.example.domain.ai.model.AiCoachResponseObservation
import com.example.domain.ai.model.AiCoachResponseRecommendation
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiRecommendationType

/** Fixtures compartilhadas pelos testes do Coach. */
object AiCoachTestData {

    fun exercise(
        id: Long,
        name: String,
        canonicalId: String? = null
    ) = ExerciseEntity(id = id, name = name, canonicalId = canonicalId)

    fun templateExercise(
        templateId: Long = 1L,
        exerciseId: Long,
        sortOrder: Int = 0,
        targetSets: Int = 3,
        minReps: Int = 8,
        maxReps: Int = 12,
        restDurationSeconds: Int = 90,
        plannedWeight: Float? = null
    ) = WorkoutTemplateExerciseEntity(
        id = exerciseId,
        templateId = templateId,
        exerciseId = exerciseId,
        sortOrder = sortOrder,
        targetSets = targetSets,
        minReps = minReps,
        maxReps = maxReps,
        restDurationSeconds = restDurationSeconds,
        plannedWeight = plannedWeight
    )

    /** Um exercício executado dentro de uma sessão, com suas séries. */
    fun executedExercise(
        exerciseSessionId: Long,
        sessionId: Long,
        exerciseRowId: Long,
        exerciseName: String,
        sets: List<SetLogEntity>
    ) = ExerciseSessionWithSets(
        exerciseSession = ExerciseSessionEntity(
            id = exerciseSessionId,
            sessionId = sessionId,
            plannedExerciseId = exerciseRowId,
            actualExerciseId = exerciseRowId,
            exerciseNameSnapshot = exerciseName
        ),
        sets = sets
    )

    fun completedSession(
        sessionId: Long,
        startedAt: Long,
        finishedAt: Long,
        exerciseRowId: Long,
        exerciseName: String,
        sets: List<SetLogEntity>
    ) = session(
        sessionId = sessionId,
        startedAt = startedAt,
        finishedAt = finishedAt,
        exercises = listOf(
            executedExercise(
                exerciseSessionId = sessionId * 100,
                sessionId = sessionId,
                exerciseRowId = exerciseRowId,
                exerciseName = exerciseName,
                sets = sets
            )
        )
    )

    fun session(
        sessionId: Long,
        startedAt: Long,
        finishedAt: Long?,
        status: SessionStatus = SessionStatus.COMPLETED,
        exercises: List<ExerciseSessionWithSets>
    ) = SessionCalendarSummary(
        session = WorkoutSessionEntity(
            id = sessionId,
            templateId = 1L,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status.name
        ),
        checkIn = null,
        exercises = exercises
    )

    fun setLog(
        exerciseSessionId: Long,
        setNumber: Int,
        weight: Float,
        repetitions: Int,
        completed: Boolean = true
    ) = SetLogEntity(
        id = exerciseSessionId * 10 + setNumber,
        exerciseSessionId = exerciseSessionId,
        setNumber = setNumber,
        weight = weight,
        repetitions = repetitions,
        completed = completed
    )

    fun personalRecord(exerciseId: Long, value: Float, date: Long) = PersonalRecordEntity(
        id = exerciseId,
        exerciseId = exerciseId,
        date = date,
        prType = PRType.MAX_WEIGHT,
        value = value
    )

    fun observation(
        exerciseId: String? = null,
        title: String = "Carga estável",
        description: String = "A carga registrada não mudou nas últimas sessões."
    ) = AiCoachResponseObservation(exerciseId = exerciseId, title = title, description = description)

    fun recommendation(
        type: String = AiRecommendationType.REVIEW_LOAD.name,
        exerciseId: String? = "supino-reto-barra",
        reason: String = "A carga não subiu nas últimas sessões.",
        confidence: Double = 0.8,
        evidence: String? = "60 kg em 3 sessões consecutivas"
    ) = AiCoachResponseRecommendation(type, exerciseId, reason, confidence, evidence)

    /** Uma resposta completa e válida, para o teste alterar só o que ele quer testar. */
    fun response(
        summary: String = "Progressão estável.",
        positiveSignals: List<AiCoachResponseObservation> = emptyList(),
        attentionPoints: List<AiCoachResponseObservation> = emptyList(),
        recommendations: List<AiCoachResponseRecommendation> = emptyList(),
        dataQuality: AiCoachResponseDataQuality? = dataQuality(AiDataQualityLevel.LIMITED)
    ) = AiCoachResponse(
        summary = summary,
        positiveSignals = positiveSignals,
        attentionPoints = attentionPoints,
        recommendations = recommendations,
        dataQuality = dataQuality
    )

    fun dataQuality(
        level: AiDataQualityLevel,
        description: String = "Análise baseada nas sessões concluídas enviadas."
    ) = AiCoachResponseDataQuality(level = level.name, description = description)

    fun dataQuality(level: String, description: String = "Base da análise.") =
        AiCoachResponseDataQuality(level = level, description = description)
}
