package com.example.exercise.premium

import androidx.compose.runtime.Composable

@Composable
fun ExerciseProgressionCard(
    method: String?,
    repRange: String?,
    rule: String?,
    sets: Int?,
    incUpper: Float?,
    incLower: Float?
) {
    com.example.presentation.exercises.components.premium.ExerciseProgressionCard(
        method = method,
        repRange = repRange,
        rule = rule,
        sets = sets,
        incUpper = incUpper?.toDouble(),
        incLower = incLower?.toDouble()
    )
}
