package com.example.feature.evolution.components.body

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.BodyMeasurement
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun WeightChart(
    measurements: List<BodyMeasurement>,
    modifier: Modifier = Modifier
) {
    val validMeasurements = remember(measurements) {
        measurements
            .filter { it.weightKg != null && it.weightKg > 0f }
            .sortedBy { it.date }
    }

    val chartPoints = remember(validMeasurements) {
        validMeasurements.map {
            ChartPoint(date = it.date, value = it.weightKg!!)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("weight_chart")
    ) {
        if (chartPoints.size < 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(SurfaceHighlight.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .testTag("weight_chart_empty_or_single"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Registre mais pesos\npara visualizar sua evolução",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.testTag("txt_insufficient_weight_history")
                    )
                }
            }
        } else {
            EvolutionLineChart(
                points = chartPoints,
                lineColor = Lime400,
                gradientColor = LimeTransparent,
                unit = "kg",
                testTag = "weight_evolution_chart"
            )
        }
    }
}
