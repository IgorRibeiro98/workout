package com.example.feature.evolution.components.performance

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.feature.evolution.components.body.ChartPoint
import com.example.feature.evolution.components.body.EvolutionLineChart
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent

@Composable
fun VolumeHistoryChart(
    points: List<VolumePoint>,
    modifier: Modifier = Modifier,
    testTag: String = "volume_history_chart"
) {
    if (points.size < 2) return

    val chartPoints = points.map {
        ChartPoint(date = it.date, value = it.volume)
    }

    EvolutionLineChart(
        points = chartPoints,
        modifier = modifier.fillMaxWidth(),
        lineColor = Lime400,
        gradientColor = LimeTransparent,
        unit = "kg",
        testTag = testTag
    )
}
