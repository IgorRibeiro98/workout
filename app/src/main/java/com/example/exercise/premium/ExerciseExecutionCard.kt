package com.example.exercise.premium

import androidx.compose.runtime.Composable

@Composable
fun ExerciseExecutionCard(
    setupJson: String?,
    stepsJson: String?,
    breathingJson: String?
) {
    com.example.presentation.exercises.components.premium.ExerciseExecutionCard(
        setupJson = setupJson,
        stepsJson = stepsJson,
        breathingJson = breathingJson
    )
}
