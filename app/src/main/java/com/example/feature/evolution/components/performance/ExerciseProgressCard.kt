package com.example.feature.evolution.components.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.calculator.PerformanceCalculator
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun ExerciseProgressCard(
    exercises: List<ExercisePerformanceEvolution>,
    modifier: Modifier = Modifier,
    testTag: String = "exercise_progress_card"
) {
    // Filter to top 5 exercises that have evolution data
    val topExercises = remember(exercises) {
        exercises.take(5)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(LimeTransparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShowChart,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Exercícios em Evolução",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Top exercícios por ganho de força",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (topExercises.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF191B1F))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nenhum histórico de exercício disponível ainda.",
                        color = TextTertiary,
                        fontSize = 13.sp
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    topExercises.forEachIndexed { index, item ->
                        ExerciseProgressItem(
                            item = item,
                            testTag = "exercise_progress_item_${item.exerciseId}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseProgressItem(
    item: ExercisePerformanceEvolution,
    modifier: Modifier = Modifier,
    testTag: String = "exercise_progress_item"
) {
    val numberFormatter = remember {
        DecimalFormat("#0.#", DecimalFormatSymbols(Locale("pt", "BR")))
    }

    val first = item.firstWeight
    val current = item.currentWeight
    val variation = item.weightVariation ?: (if (first != null && current != null) current - first else null)
    val percentage = PerformanceCalculator.calculatePercentageGrowth(first, current)

    val isPositive = variation != null && variation > 0f
    val isNegative = variation != null && variation < 0f

    val badgeColor = when {
        isPositive -> Lime400
        isNegative -> Color(0xFFFF5252)
        else -> TextSecondary
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF191B1F))
            .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag(testTag)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.exerciseName,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                if (variation != null) {
                    val sign = if (variation > 0f) "+" else ""
                    val variationText = "$sign${numberFormatter.format(variation)}kg"
                    val percentageText = if (percentage != 0f) " ($sign${numberFormatter.format(percentage)}%)" else ""

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$variationText$percentageText",
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progression Row (e.g. 40kg -> 70kg)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (first != null) {
                        Column {
                            Text(
                                text = "Início",
                                color = TextTertiary,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "${numberFormatter.format(first)}kg",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    if (current != null) {
                        Column {
                            Text(
                                text = "Atual",
                                color = TextTertiary,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "${numberFormatter.format(current)}kg",
                                color = Lime400,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = "Sem carga registrada",
                            color = TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }

                Text(
                    text = "${item.totalExecutions}x realizado",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
