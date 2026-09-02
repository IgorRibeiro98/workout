package com.example.components.workout.execution

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun WorkoutProgressHeader(
    currentExerciseIndex: Int,
    totalExercises: Int,
    categoryOrMuscle: String?,
    modifier: Modifier = Modifier
) {
    if (totalExercises <= 0) return

    val currentDisplay = (currentExerciseIndex + 1).coerceIn(1, totalExercises)
    val progressFraction = currentDisplay.toFloat() / totalExercises.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        label = "workoutProgress"
    )
    val percentage = (progressFraction * 100).toInt()

    val labelText = buildString {
        if (!categoryOrMuscle.isNullOrBlank()) {
            append(categoryOrMuscle)
            append(" • ")
        }
        append("Exercício $currentDisplay de $totalExercises")
        append(" • $percentage%")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("workout_progress_header"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = labelText,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Sleek progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Lime400)
            )
        }
    }
}
