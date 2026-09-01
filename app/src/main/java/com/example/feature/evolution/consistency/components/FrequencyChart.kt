package com.example.feature.evolution.consistency.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import com.example.feature.evolution.components.body.ChartPoint
import com.example.feature.evolution.components.body.EvolutionLineChart
import com.example.ui.theme.BorderLight
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FrequencyChart(
    frequencyHistory: List<WorkoutFrequencyPoint>,
    modifier: Modifier = Modifier
) {
    val dateFormatter = SimpleDateFormat("dd/MM", Locale("pt", "BR"))
    val chartPoints = frequencyHistory.map { fp ->
        val dateStr = dateFormatter.format(Date(fp.date))
        ChartPoint(
            date = fp.date,
            value = fp.sessions.toFloat(),
            formattedDate = dateStr,
            label = "${fp.sessions}",
            tooltipText = "Semana de $dateStr\n${fp.sessions} ${if (fp.sessions == 1) "treino" else "treinos"}"
        )
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("frequency_chart_container")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Treinos por semana",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Frequência de treinos agrupada por semana",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (chartPoints.isEmpty()) {
                Text(
                    text = "Complete treinos semanalmente para acompanhar sua frequência.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("frequency_chart_empty")
                )
            } else {
                EvolutionLineChart(
                    points = chartPoints,
                    unit = "treinos",
                    testTag = "frequency_chart"
                )
            }
        }
    }
}
