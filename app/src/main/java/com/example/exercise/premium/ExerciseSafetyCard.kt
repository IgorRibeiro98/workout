package com.example.exercise.premium

import androidx.compose.runtime.Composable

@Composable
fun ExerciseSafetyCard(
    riskLevel: String?,
    attentionPointsJson: String?,
    discomfortsJson: String?
) {
    com.example.presentation.exercises.components.premium.ExerciseSafetyCard(
        riskLevel = riskLevel,
        attentionPointsJson = attentionPointsJson,
        discomfortsJson = discomfortsJson
    )
}
