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

    fun completedSession(
        sessionId: Long,
        startedAt: Long,
        finishedAt: Long,
        exerciseRowId: Long,
        exerciseName: String,
        sets: List<SetLogEntity>
    ) = SessionCalendarSummary(
        session = WorkoutSessionEntity(
            id = sessionId,
            templateId = 1L,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = SessionStatus.COMPLETED.name
        ),
        checkIn = null,
        exercises = listOf(
            ExerciseSessionWithSets(
                exerciseSession = ExerciseSessionEntity(
                    id = sessionId * 100,
                    sessionId = sessionId,
                    plannedExerciseId = exerciseRowId,
                    actualExerciseId = exerciseRowId,
                    exerciseNameSnapshot = exerciseName
                ),
                sets = sets
            )
        )
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
}
