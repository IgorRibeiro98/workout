package com.example.feature.evolution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.feature.evolution.components.ConsistencyCard
import com.example.feature.evolution.components.EmptyEvolutionCard
import com.example.feature.evolution.components.EvolutionHeader
import com.example.feature.evolution.components.EvolutionMetricCard
import com.example.feature.evolution.components.WeightEvolutionCard
import com.example.feature.evolution.components.body.BodyEvolutionSection
import com.example.feature.evolution.performance.components.PerformanceSection
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun EvolutionScreen(
    viewModel: EvolutionViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToBodyEvolution: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            EvolutionHeader(
                onNavigateBack = onNavigateBack
            )
        },
        containerColor = BackgroundDark,
        modifier = modifier.fillMaxSize().testTag("evolution_screen")
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("evolution_loading"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Lime400,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Carregando evolução...",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }

                uiState.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .testTag("evolution_error_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.error ?: "Não foi possível carregar sua evolução.",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadEvolution() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Lime400,
                                contentColor = BackgroundDark
                            ),
                            modifier = Modifier.testTag("evolution_retry_button")
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Tentar novamente", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                uiState.isEmpty -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("evolution_empty_state"),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            EmptyEvolutionCard()
                        }
                    }
                }

                else -> {
                    val summary = uiState.summary
                    val performance = uiState.performance
                    val consistency = uiState.consistency

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("evolution_content_list"),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Card Principal de Peso
                        item {
                            WeightEvolutionCard(
                                summary = summary,
                                onRegisterWeightClick = onNavigateToBodyEvolution
                            )
                        }

                        // 2. Grid de Métricas Principais
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                EvolutionMetricCard(
                                    title = "Treinos",
                                    value = "${summary?.totalWorkoutSessions ?: 0}",
                                    description = "treinos concluídos",
                                    icon = Icons.Default.FitnessCenter,
                                    modifier = Modifier.weight(1f),
                                    testTag = "metric_card_workouts"
                                )

                                EvolutionMetricCard(
                                    title = "Dias ativos",
                                    value = "${summary?.trainingDays ?: 0}",
                                    description = "dias treinando",
                                    icon = Icons.Default.CalendarToday,
                                    modifier = Modifier.weight(1f),
                                    testTag = "metric_card_days"
                                )
                            }
                        }

                        item {
                            EvolutionMetricCard(
                                title = "Exercícios",
                                value = "${summary?.totalExercisesPerformed ?: 0}",
                                description = "exercícios feitos",
                                icon = Icons.Default.FormatListNumbered,
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "metric_card_exercises"
                            )
                        }

                        // 3. Card de Consistência
                        item {
                            ConsistencyCard(
                                consistency = consistency,
                                summary = summary
                            )
                        }

                        // 4. Seção de Evolução Corporal
                        item {
                            BodyEvolutionSection(
                                measurements = uiState.measurements,
                                currentWeight = uiState.currentWeight,
                                initialWeight = uiState.initialWeight,
                                weightVariation = uiState.weightVariation,
                                currentHeight = uiState.currentHeight,
                                bmi = uiState.bmi,
                                bmiCategory = uiState.bmiCategory,
                                onRegisterMeasurementClick = onNavigateToBodyEvolution
                            )
                        }

                        // 5. Seção de Performance de Treino
                        item {
                            PerformanceSection(
                                summary = uiState.performanceSummary,
                                exercises = uiState.exerciseEvolutions,
                                records = uiState.personalRecords,
                                volumeHistory = uiState.volumeHistory,
                                allExercises = uiState.exerciseEvolutions,
                                selectedExerciseId = uiState.selectedExerciseId,
                                selectedExerciseName = uiState.selectedExerciseName,
                                strengthHistory = uiState.strengthHistory,
                                onSelectExercise = { viewModel.selectExercise(it) },
                                onRetry = { viewModel.loadEvolution() }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
