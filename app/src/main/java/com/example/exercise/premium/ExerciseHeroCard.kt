package com.example.exercise.premium

import androidx.compose.runtime.Composable

@Composable
fun ExerciseHeroCard(
    title: String,
    primaryMuscle: String?,
    equipment: String?,
    difficulty: String?,
    mediaUrl: String?
) {
    com.example.presentation.exercises.components.premium.ExerciseHeroCard(
        title = title,
        primaryMuscle = primaryMuscle,
        equipment = equipment,
        difficulty = difficulty,
        mediaUrl = mediaUrl
    )
}
