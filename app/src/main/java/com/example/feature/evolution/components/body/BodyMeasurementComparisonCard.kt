package com.example.feature.evolution.components.body

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.domain.evolution.model.BodyMeasurement

@Composable
fun BodyMeasurementComparisonCard(
    measurements: List<BodyMeasurement>,
    modifier: Modifier = Modifier
) {
    BodyComparisonCard(
        measurements = measurements,
        modifier = modifier
    )
}
