package com.example.feature.evolution.performance.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.feature.evolution.performance.PerformanceUiState
import com.example.feature.evolution.performance.chart.PerformanceChartsSection
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun PerformanceSection(
    uiState: PerformanceUiState,
    onRetry: () -> Unit = {},
    onSelectExercise: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    testTag: String = "performance_section"
) {
    PerformanceSectionContent(
        isLoading = uiState.isLoading,
        error = uiState.error,
        summary = uiState.summary,
        topExercises = uiState.topExercises,
        personalRecords = uiState.personalRecords,
        volumeHistory = uiState.volumeHistory,
        availableExercises = uiState.allExercises.ifEmpty { uiState.topExercises },
        selectedExerciseId = uiState.selectedExerciseId,
        selectedExerciseName = uiState.selectedExerciseName,
        strengthHistory = uiState.strengthHistory,
        onSelectExercise = onSelectExercise,
        onRetry = onRetry,
        modifier = modifier,
        testTag = testTag
    )
}

@Composable
fun PerformanceSection(
    summary: WorkoutPerformanceSummary?,
    exercises: List<ExercisePerformanceEvolution>,
    records: List<PersonalRecord>,
    modifier: Modifier = Modifier,
    volumeHistory: List<VolumePoint> = emptyList(),
    allExercises: List<ExercisePerformanceEvolution> = emptyList(),
    selectedExerciseId: String? = null,
    selectedExerciseName: String? = null,
    strengthHistory: List<StrengthPoint> = emptyList(),
    onSelectExercise: (String) -> Unit = {},
    isLoading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    testTag: String = "performance_section"
) {
    PerformanceSectionContent(
        isLoading = isLoading,
        error = error,
        summary = summary,
        topExercises = exercises,
        personalRecords = records,
        volumeHistory = volumeHistory,
        availableExercises = allExercises.ifEmpty { exercises },
        selectedExerciseId = selectedExerciseId,
        selectedExerciseName = selectedExerciseName,
        strengthHistory = strengthHistory,
        onSelectExercise = onSelectExercise,
        onRetry = onRetry,
        modifier = modifier,
        testTag = testTag
    )
}

@Composable
private fun PerformanceSectionContent(
    isLoading: Boolean,
    error: String?,
    summary: WorkoutPerformanceSummary?,
    topExercises: List<ExercisePerformanceEvolution>,
    personalRecords: List<PersonalRecord>,
    volumeHistory: List<VolumePoint>,
    availableExercises: List<ExercisePerformanceEvolution>,
    selectedExerciseId: String?,
    selectedExerciseName: String?,
    strengthHistory: List<StrengthPoint>,
    onSelectExercise: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "performance_section"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Title
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
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = "Performance de treino",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Cargas, volume e progressão de força",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        when {
            isLoading -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("performance_loading_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Lime400,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Carregando performance...",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            error != null -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("performance_error_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Não foi possível carregar sua performance.",
                            color = Color(0xFFFF8A80),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Lime400,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("performance_retry_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "Tentar novamente",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            summary == null || summary.totalSessions == 0 -> {
                // Estado Vazio (PARTE 7)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("performance_empty_state"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(LimeTransparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitnessCenter,
                                contentDescription = null,
                                tint = Lime400,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Comece seus treinos",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Complete seus primeiros exercícios para acompanhar sua evolução.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            else -> {
                // 1. Resumo de Performance
                PerformanceSummaryCard(
                    summary = summary,
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Volume Treinado Destacado
                VolumeSummaryCard(
                    totalVolumeKg = summary.totalVolume,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. Gráficos de Performance e Progressão (Volume e Carga)
                PerformanceChartsSection(
                    volumeHistory = volumeHistory,
                    availableExercises = availableExercises,
                    selectedExerciseId = selectedExerciseId,
                    selectedExerciseName = selectedExerciseName,
                    strengthHistory = strengthHistory,
                    onSelectExercise = onSelectExercise,
                    modifier = Modifier.fillMaxWidth()
                )

                // 4. Exercícios que mais evoluíram (Top 5)
                if (topExercises.isNotEmpty()) {
                    ExerciseProgressCard(
                        exercises = topExercises,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 5. Melhores marcas (PRs)
                if (personalRecords.isNotEmpty()) {
                    PersonalRecordCard(
                        records = personalRecords,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
