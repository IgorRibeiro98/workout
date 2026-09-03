package com.example.components.workout.execution

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.workout.execution.ExerciseExecutionContext
import com.example.ui.theme.*

@Composable
fun ExercisePrescriptionCard(
    context: ExerciseExecutionContext?,
    modifier: Modifier = Modifier
) {
    val targetWeight = context?.suggestedLoad
    val targetReps = context?.targetReps
    val isDurationMode = context?.isDurationMode == true

    // If no target weight or reps are defined, don't render an empty card
    if (targetWeight == null && targetReps == null) return

    val repsStr = when {
        isDurationMode && targetReps != null && targetReps.first == targetReps.last -> "${targetReps.first}s"
        isDurationMode && targetReps != null -> "${targetReps.first}-${targetReps.last}s"
        targetReps != null && targetReps.first == targetReps.last -> "${targetReps.first} reps"
        targetReps != null -> "${targetReps.first}-${targetReps.last} reps"
        else -> null
    }

    val weightStr = if (targetWeight != null && targetWeight > 0f) {
        if (targetWeight % 1f == 0f) "${targetWeight.toInt()}kg" else "${targetWeight}kg"
    } else null

    val items = listOfNotNull(repsStr, weightStr, "RIR 2-3")
    val targetText = items.joinToString("  ·  ")

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("exercise_prescription_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Prescrição",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = targetText,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
