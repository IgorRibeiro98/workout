package com.example.feature.evolution.consistency.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import com.example.feature.evolution.consistency.ConsistencyViewModel
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ConsistencySection(
    viewModel: ConsistencyViewModel,
    modifier: Modifier = Modifier,
    testTag: String = "consistency_section"
) {
    val uiState by viewModel.uiState.collectAsState()
    ConsistencySection(
        summary = uiState.summary,
        progress = uiState.consistencyProgress,
        weeklyConsistencies = uiState.weeklyConsistencies,
        frequencyHistory = uiState.frequencyHistory,
        workoutTimestamps = uiState.workoutTimestamps,
        modifier = modifier,
        testTag = testTag
    )
}

@Composable
fun ConsistencySection(
    summary: WorkoutConsistencySummary?,
    progress: ConsistencyProgress? = null,
    weeklyConsistencies: List<WeeklyConsistency> = emptyList(),
    frequencyHistory: List<WorkoutFrequencyPoint> = emptyList(),
    workoutTimestamps: List<Long> = emptyList(),
    modifier: Modifier = Modifier,
    testTag: String = "consistency_section"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Consistência e Hábitos",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Acompanhamento de frequência, sequências semanais e disciplina",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // 1. Card de Sequência e Consistência Semanal
        StreakCard(
            summary = summary,
            progress = progress
        )

        // 2. Histórico Semanal de Consistência
        if (weeklyConsistencies.isNotEmpty()) {
            WeeklyConsistencyHistoryCard(weeklyConsistencies = weeklyConsistencies)
        }

        // 3. Card de Resumo de Hábitos
        HabitSummaryCard(summary = summary)

        // 4. Gráfico de Frequência Semanal
        FrequencyChart(frequencyHistory = frequencyHistory)

        // 5. Calendário Simples do Mês
        WorkoutCalendarCard(workoutTimestamps = workoutTimestamps)
    }
}
