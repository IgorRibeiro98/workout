package com.example.exercise.premium

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ExerciseHeroCard(
    title: String,
    primaryMuscle: String?,
    equipment: String?,
    difficulty: String?,
    mediaUrl: String?,
    subtitle: String? = null,
    movementPattern: String? = null,
    modifier: Modifier = Modifier
) {
    com.example.presentation.exercises.components.premium.ExerciseHeroCard(
        title = title,
        primaryMuscle = primaryMuscle,
        equipment = equipment,
        difficulty = difficulty,
        mediaUrl = mediaUrl,
        subtitle = subtitle,
        movementPattern = movementPattern,
        modifier = modifier
    )
}

