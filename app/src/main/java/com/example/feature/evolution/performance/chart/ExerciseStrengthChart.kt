package com.example.feature.evolution.performance.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.feature.evolution.components.body.EvolutionLineChart
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun ExerciseStrengthChart(
    exercises: List<ExercisePerformanceEvolution>,
    selectedExerciseId: String?,
    selectedExerciseName: String?,
    strengthHistory: List<StrengthPoint>,
    onSelectExercise: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "exercise_strength_chart"
) {
    val chartPoints = PerformanceChartMapper.mapStrengthHistoryToChartPoints(strengthHistory, selectedExerciseName)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Título & Subtítulo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(LimeTransparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = selectedExerciseName ?: "Evolução de Carga",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Carga máxima realizada por treino",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Seletor de Exercício
            ExerciseSelector(
                exercises = exercises,
                selectedExerciseId = selectedExerciseId,
                onExerciseSelected = onSelectExercise,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (strengthHistory.isEmpty()) {
                // PARTE 7: Estado vazio de força
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E2124))
                        .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
                        .padding(20.dp)
                        .testTag("exercise_strength_empty"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Ainda não existem dados suficientes para este exercício.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                EvolutionLineChart(
                    points = chartPoints,
                    modifier = Modifier.fillMaxWidth(),
                    lineColor = Lime400,
                    gradientColor = LimeTransparent,
                    unit = "kg",
                    testTag = "strength_line_chart"
                )
            }
        }
    }
}
