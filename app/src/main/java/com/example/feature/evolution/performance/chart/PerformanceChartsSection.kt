package com.example.feature.evolution.performance.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PerformanceChartsSection(
    volumeHistory: List<VolumePoint>,
    availableExercises: List<ExercisePerformanceEvolution>,
    selectedExerciseId: String?,
    selectedExerciseName: String?,
    strengthHistory: List<StrengthPoint>,
    onSelectExercise: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "performance_charts_section"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(LimeTransparent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoGraph,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = "Gráficos de evolução",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tendência de volume e força no tempo",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // 1. Gráfico de Volume
        VolumeHistoryChart(
            volumePoints = volumeHistory,
            modifier = Modifier.fillMaxWidth()
        )

        // 2. Seletor de Exercício e Gráfico de Força
        if (availableExercises.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExerciseSelector(
                    exercises = availableExercises,
                    selectedExerciseId = selectedExerciseId,
                    onExerciseSelected = onSelectExercise,
                    modifier = Modifier.fillMaxWidth()
                )

                ExerciseStrengthChart(
                    exerciseName = selectedExerciseName,
                    strengthPoints = strengthHistory,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
