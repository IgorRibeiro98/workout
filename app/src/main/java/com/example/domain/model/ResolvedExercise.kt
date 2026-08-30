package com.example.domain.model

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.domain.engine.ResolvedMedia

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
)
