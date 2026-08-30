package com.example.domain.engine

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.domain.model.ResolvedExercise

object ExerciseResolver {

    /**
     * Resolves the effective attributes of an exercise following the strict precedence hierarchy:
     * - displayName: override.displayName (if not blank) ?: system.name
     * - notes: override.notes (if not blank) ?: system.description
     * - photo/media: override.customPhotoUri ?: system/remote gif/image
     * - defaultRestSeconds: override.defaultRestSeconds
     * - isUserCreated: strictly 0 for canonical exercises with non-blank canonicalId, otherwise exercise.isUserCreated
     */
    fun resolve(
        exercise: ExerciseEntity,
        override: ExerciseUserOverrideEntity? = null,
        showGifs: Boolean = true
    ): ResolvedExercise {
        val displayName = override?.displayName?.takeIf { it.isNotBlank() } ?: exercise.name
        val notes = override?.notes?.takeIf { it.isNotBlank() } ?: exercise.description
        val resolvedMedia = ExerciseMediaResolver.resolveMedia(exercise, override, showGifs)
        val defaultRest = override?.defaultRestSeconds
        val secMuscles = exercise.secondaryMuscles
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // Canonical classification check: If canonicalId is present and non-blank, it is NOT user created
        val isUser = if (!exercise.canonicalId.isNullOrBlank()) false else exercise.isUserCreated

        return ResolvedExercise(
            id = exercise.id,
            canonicalId = exercise.canonicalId,
            slug = exercise.slug,
            displayName = displayName,
            nameEn = exercise.nameEn,
            primaryMuscle = exercise.primaryMuscle,
            secondaryMuscles = secMuscles,
            equipment = exercise.equipment,
            movementPattern = exercise.movementPattern,
            substitutionGroup = exercise.substitutionGroup,
            notes = notes,
            resolvedMedia = resolvedMedia,
            defaultRestSeconds = defaultRest,
            isUserCreated = isUser,
            isCustomPhoto = resolvedMedia.isCustomPhoto,
            rawExercise = exercise,
            override = override
        )
    }

    /**
     * Resolves a list of exercises with their corresponding overrides.
     */
    fun resolveAll(
        exercises: List<ExerciseEntity>,
        overrides: Map<Long, ExerciseUserOverrideEntity>,
        showGifs: Boolean = true
    ): List<ResolvedExercise> {
        return exercises.map { ex ->
            resolve(ex, overrides[ex.id], showGifs)
        }
    }
}
