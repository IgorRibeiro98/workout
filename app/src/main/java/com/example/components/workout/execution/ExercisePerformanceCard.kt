package com.example.components.workout.execution

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.RirFormatter
import com.example.domain.workout.execution.ExerciseExecutionContext
import com.example.ui.theme.*

@Composable
fun ExercisePerformanceCard(
    context: ExerciseExecutionContext?,
    onViewHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val lastPerf = context?.lastPerformance
    val pr = context?.personalRecord
    val isFirstTime = context?.isFirstTime == true || (lastPerf == null && pr == null)

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (onViewHistory != null && !isFirstTime) {
                    Modifier.clickable(onClick = onViewHistory)
                } else Modifier
            )
            .testTag("exercise_performance_card")
    ) {
        if (isFirstTime) {
            // First time state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = Lime400.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Primeira execução deste exercício",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Registre sua carga inicial para criar seu histórico de evolução",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        } else {
            // Historical performance state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Last performance
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Última vez",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Best record
                    if (pr != null) {
                        val prWeightStr = if (pr.maxWeight % 1f == 0f) "${pr.maxWeight.toInt()}" else "${pr.maxWeight}"
                        Surface(
                            color = Color(0xFFFFB74D).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.3f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EmojiEvents,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Melhor: ${prWeightStr}kg × ${pr.repsAtMaxWeight}",
                                    color = Color(0xFFFFB74D),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Last execution details
                if (lastPerf != null) {
                    val weightStr = if (lastPerf.weight % 1f == 0f) "${lastPerf.weight.toInt()}" else "${lastPerf.weight}"
                    val daysAgoStr = when (lastPerf.daysAgo) {
                        null -> ""
                        0L -> "hoje"
                        1L -> "ontem"
                        else -> "há ${lastPerf.daysAgo} dias"
                    }
                    val rirLabel = RirFormatter.formatSecondaryRir(lastPerf.rir)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${weightStr}kg × ${lastPerf.reps} reps",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )

                        if (rirLabel.isNotBlank()) {
                            Surface(
                                color = SurfaceDark,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, BorderLight)
                            ) {
                                Text(
                                    text = rirLabel,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (daysAgoStr.isNotBlank()) {
                            Text(
                                text = "• $daysAgoStr",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
