package com.example.feature.evolution.performance.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.feature.evolution.components.performance.VolumeHistoryChart

@Composable
fun PerformanceChartsSection(
    volumeHistory: List<VolumePoint>,
    exercises: List<ExercisePerformanceEvolution> = emptyList(),
    availableExercises: List<ExercisePerformanceEvolution> = exercises,
    selectedExerciseId: String?,
    selectedExerciseName: String?,
    strengthHistory: List<StrengthPoint>,
    onSelectExercise: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "performance_charts_section"
) {
    val exerciseList = availableExercises.ifEmpty { exercises }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Gráfico de Volume Treinado
        VolumeHistoryChart(
            points = volumeHistory,
            modifier = Modifier.fillMaxWidth()
        )

        // 2. Gráfico de Evolução de Carga com Seletor de Exercício
        ExerciseStrengthChart(
            exercises = exerciseList,
            selectedExerciseId = selectedExerciseId,
            selectedExerciseName = selectedExerciseName,
            strengthHistory = strengthHistory,
            onSelectExercise = onSelectExercise,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
