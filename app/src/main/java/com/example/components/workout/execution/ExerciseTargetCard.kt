package com.example.components.workout.execution

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.domain.workout.execution.ExerciseExecutionContext

@Deprecated("Use ExercisePrescriptionCard instead", ReplaceWith("ExercisePrescriptionCard(context, modifier)"))
@Composable
fun ExerciseTargetCard(
    context: ExerciseExecutionContext?,
    modifier: Modifier = Modifier
) {
    ExercisePrescriptionCard(context = context, modifier = modifier)
}
