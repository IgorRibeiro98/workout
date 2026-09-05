package com.example.domain.ai

import com.example.data.local.ExerciseEntity
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiExerciseLoadEvidenceContext
import com.example.domain.ai.model.AiGeneratedWorkoutExerciseResponse
import com.example.domain.ai.model.AiGeneratedWorkoutResponse
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.model.WorkoutGoal
import com.example.domain.engine.MuscleGroup

/** Fixtures compartilhadas pelos testes de geração de treino. */
object WorkoutGenerationTestData {

    fun catalogExercise(
        id: Long,
        name: String,
        canonicalId: String?,
        primaryMuscle: String,
        equipment: String? = "Barra",
        secondaryMuscles: String? = null,
        active: Boolean = true,
        isBodyweight: Boolean = false
    ) = ExerciseEntity(
        id = id,
        name = name,
        canonicalId = canonicalId,
        primaryMuscle = primaryMuscle,
        secondaryMuscles = secondaryMuscles,
        equipment = equipment,
        active = active,
        isBodyweight = isBodyweight
    )

    fun preferences(
        goal: WorkoutGoal = WorkoutGoal.HYPERTROPHY,
        durationMinutes: Int = 50,
        focus: List<MuscleGroup> = listOf(MuscleGroup.CHEST),
        equipment: Set<EquipmentAvailability> = setOf(EquipmentAvailability.FULL_GYM),
        excluded: Set<String> = emptySet(),
        notes: String? = null
    ) = WorkoutGenerationPreferences(
        goal = goal,
        durationMinutes = durationMinutes,
        focusMuscleGroups = focus,
        availableEquipment = equipment,
        excludedExerciseIds = excluded,
        notes = notes
    )

    fun candidate(
        exerciseId: String,
        name: String = exerciseId,
        muscleGroup: String = MuscleGroup.CHEST.displayName,
        equipment: String? = "Barra"
    ) = AiCandidateExerciseContext(
        exerciseId = exerciseId,
        name = name,
        muscleGroup = muscleGroup,
        equipment = equipment
    )

    fun context(
        candidates: List<AiCandidateExerciseContext> = listOf(
            candidate("supino-reto-barra", "Supino reto com barra"),
            candidate("crucifixo-halteres", "Crucifixo com halteres")
        ),
        loadEvidence: List<AiExerciseLoadEvidenceContext> = emptyList(),
        durationMinutes: Int = 50
    ) = AiWorkoutGenerationContext(
        goal = WorkoutGoal.HYPERTROPHY.name,
        goalGuidance = WorkoutGoal.HYPERTROPHY.guidance,
        durationMinutes = durationMinutes,
        focusMuscleGroups = listOf(MuscleGroup.CHEST.displayName),
        candidateExercises = candidates,
        loadEvidence = loadEvidence
    )

    fun exerciseResponse(
        exerciseId: String,
        order: Int,
        sets: Int = 4,
        minReps: Int = 8,
        maxReps: Int = 12,
        restSeconds: Int = 90,
        weightKg: Double? = null,
        reason: String = "Movimento principal do treino."
    ) = AiGeneratedWorkoutExerciseResponse(
        exerciseId = exerciseId,
        order = order,
        sets = sets,
        minReps = minReps,
        maxReps = maxReps,
        restSeconds = restSeconds,
        weightKg = weightKg,
        reason = reason
    )

    /** Uma proposta completa e válida; o teste altera só o que quer testar. */
    fun response(
        name: String = "Peito e tríceps",
        exercises: List<AiGeneratedWorkoutExerciseResponse> = listOf(
            exerciseResponse("supino-reto-barra", order = 1),
            exerciseResponse("crucifixo-halteres", order = 2, sets = 3, restSeconds = 75)
        ),
        explanation: String = "O supino vem primeiro por ser o movimento mais exigente.",
        insufficientCandidates: Boolean = false
    ) = AiGeneratedWorkoutResponse(
        name = name,
        exercises = exercises,
        explanation = explanation,
        insufficientCandidates = insufficientCandidates
    )
}
