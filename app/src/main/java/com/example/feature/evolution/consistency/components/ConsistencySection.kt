package com.example.feature.evolution.consistency.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.feature.evolution.consistency.ConsistencyViewModel

@Composable
fun ConsistencySection(
    viewModel: ConsistencyViewModel,
    modifier: Modifier = Modifier,
    testTag: String = "consistency_section"
) {
    val uiState by viewModel.uiState.collectAsState()
    ConsistencySection(
        summary = uiState.summary,
        frequencyHistory = uiState.frequencyHistory,
        workoutTimestamps = uiState.workoutTimestamps,
        modifier = modifier,
        testTag = testTag
    )
}

@Composable
fun ConsistencySection(
    summary: WorkoutConsistencySummary?,
    frequencyHistory: List<WorkoutFrequencyPoint>,
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
                text = "Acompanhamento de frequência, sequências e disciplina",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // 1. Card de Resumo de Hábitos
        HabitSummaryCard(summary = summary)

        // 2. Card de Sequência (Streak)
        StreakCard(summary = summary)

        // 3. Gráfico de Frequência Semanal
        FrequencyChart(frequencyHistory = frequencyHistory)

        // 4. Calendário Simples do Mês
        WorkoutCalendarCard(workoutTimestamps = workoutTimestamps)
    }
}
