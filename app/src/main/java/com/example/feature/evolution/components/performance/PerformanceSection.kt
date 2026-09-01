package com.example.feature.evolution.components.performance

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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun PerformanceSection(
    summary: WorkoutPerformanceSummary?,
    exercises: List<ExercisePerformanceEvolution>,
    records: List<PersonalRecord>,
    volumeHistory: List<VolumePoint>,
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

        if (summary == null || summary.totalSessions == 0) {
            // Empty State (Teste 5: Usuário sem treinos)
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
                        text = "Complete treinos para acompanhar sua evolução",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Suas cargas, volume movimentado e recordes pessoais aparecerão aqui conforme você registrar seus treinos.",
                        color = TextTertiary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // 1. Resumo de Performance
            PerformanceSummaryCard(
                summary = summary,
                modifier = Modifier.fillMaxWidth()
            )

            // 2. Volume Total & Histórico
            VolumeCard(
                totalVolume = summary.totalVolume,
                volumeHistory = volumeHistory,
                modifier = Modifier.fillMaxWidth()
            )

            // 3. Exercícios que Evoluíram (Top 5 por maior evolução percentual)
            if (exercises.isNotEmpty()) {
                ExerciseProgressCard(
                    exercises = exercises,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 4. Recordes Pessoais (PRs)
            if (records.isNotEmpty()) {
                PersonalRecordCard(
                    records = records,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
