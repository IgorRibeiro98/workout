package com.example.exercise.premium

import androidx.compose.runtime.Composable

@Composable
fun ExerciseSubstitutionCard(
    sameMovement: String?,
    sameMuscle: String?,
    notRecommended: String?
) {
    com.example.presentation.exercises.components.premium.ExerciseSubstitutionCard(
        sameMovement = sameMovement,
        sameMuscle = sameMuscle,
        notRecommended = notRecommended
    )
}
