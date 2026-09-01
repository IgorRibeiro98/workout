package com.example.domain.exercise.import

data class ExternalExerciseDTO(
    val externalId: String,
    val name: String,
    val bodyParts: List<String> = emptyList(),
    val targetMuscles: List<String> = emptyList(),
    val equipment: String? = null,
    val instructions: List<String> = emptyList(),
    val mediaUrls: List<String> = emptyList()
)
