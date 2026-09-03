package com.example.domain.model

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.domain.engine.ResolvedMedia

enum class ExerciseExecutionMode {
    REPS,
    DURATION
}

data class ResolvedExercise(
    val id: Long,
    val canonicalId: String?,
    val slug: String?,
    val displayName: String,
    val nameEn: String?,
    val primaryMuscle: String?,
    val secondaryMuscles: List<String>,
    val equipment: String?,
    val movementPattern: String?,
    val substitutionGroup: String?,
    val notes: String?,
    val resolvedMedia: ResolvedMedia,
    val defaultRestSeconds: Int?,
    val isUserCreated: Boolean,
    val isCustomPhoto: Boolean,
    val rawExercise: ExerciseEntity,
    val override: ExerciseUserOverrideEntity? = null
) {
    val executionMode: ExerciseExecutionMode
        get() {
            val name = displayName.lowercase()
            val movement = movementPattern?.lowercase() ?: ""
            val rawCategory = rawExercise.category?.lowercase() ?: ""
            val notesStr = (notes ?: "").lowercase()
            return if (
                name.contains("prancha") ||
                name.contains("plank") ||
                name.contains("isometria") ||
                name.contains("isométrico") ||
                name.contains("isometric") ||
                name.contains("suspens") ||
                name.contains("hang") ||
                name.contains("wall sit") ||
                name.contains("esteira") ||
                name.contains("bicicleta") ||
                name.contains("cardio") ||
                movement.contains("isometric") ||
                rawCategory.contains("cardio") ||
                notesStr.contains("isometria")
            ) {
                ExerciseExecutionMode.DURATION
            } else {
                ExerciseExecutionMode.REPS
            }
        }
}
