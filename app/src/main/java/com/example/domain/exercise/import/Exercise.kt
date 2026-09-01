package com.example.domain.exercise.import

data class Exercise(
    val id: Long = 0,
    val name: String,
    val normalizedName: String? = null,
    val muscleGroups: List<String> = emptyList(),
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String? = null,
    val instructions: List<String> = emptyList(),
    val executionTips: String? = null,
    val commonMistakes: String? = null,
    val alternatives: List<String> = emptyList(),
    val youtubeUrl: String? = null,
    val media: List<String> = emptyList(),
    val source: String? = null,
    val externalReferences: List<ExternalExerciseReference> = emptyList(),
    val origin: ExerciseOrigin = ExerciseOrigin.SYSTEM,
    val isCurated: Boolean = false
)
