package com.example.exercise.premium

import androidx.compose.runtime.Composable

@Composable
fun ExerciseAboutCard(
    description: String?,
    primaryMuscles: String?,
    secondaryMuscles: String?,
    equipment: String?,
    difficulty: String?
) {
    com.example.presentation.exercises.components.premium.ExerciseAboutCard(
        description = description,
        primaryMuscles = primaryMuscles,
        secondaryMuscles = secondaryMuscles,
        equipment = equipment,
        difficulty = difficulty
    )
}
