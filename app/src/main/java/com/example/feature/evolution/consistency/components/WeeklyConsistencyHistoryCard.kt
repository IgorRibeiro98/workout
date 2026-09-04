package com.example.feature.evolution.consistency.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WeeklyConsistencyHistoryCard(
    weeklyConsistencies: List<WeeklyConsistency>,
    modifier: Modifier = Modifier
) {
    if (weeklyConsistencies.isEmpty()) return

    // Show latest up to 8 weeks in chronological or recent order
    val recentWeeks = weeklyConsistencies.takeLast(8).reversed()
    val formatter = DateTimeFormatter.ofPattern("dd MMM", Locale("pt", "BR"))

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("weekly_consistency_history_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Lime400.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Histórico Semanal",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                recentWeeks.forEach { week ->
                    val monday = LocalDate.ofEpochDay(week.weekStartEpochDay)
                    val sunday = monday.plusDays(6)
                    val dateLabel = "${monday.format(formatter).uppercase()} - ${sunday.format(formatter).uppercase()}"

                    val isCompleted = week.status == WeeklyConsistencyStatus.COMPLETED
                    val isInProgress = week.status == WeeklyConsistencyStatus.IN_PROGRESS
                    val isMissed = week.status == WeeklyConsistencyStatus.MISSED

                    Surface(
                        color = SurfaceHighlight,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isCompleted -> Lime400.copy(alpha = 0.2f)
                                                isInProgress -> SurfaceDark
                                                else -> SurfaceDark
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        isCompleted -> Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Concluída",
                                            tint = Lime400,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        isInProgress -> Icon(
                                            imageVector = Icons.Default.HourglassTop,
                                            contentDescription = "Em andamento",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        else -> Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Incompleta",
                                            tint = TextSecondary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = dateLabel,
                                    color = if (isInProgress) Lime400 else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isInProgress) FontWeight.Bold else FontWeight.Medium
                                )
                            }

                            Surface(
                                color = if (isCompleted) Lime400.copy(alpha = 0.15f) else SurfaceDark,
                                shape = RoundedCornerShape(6.dp),
                                border = if (isCompleted) BorderStroke(1.dp, Lime400.copy(alpha = 0.4f)) else null
                            ) {
                                Text(
                                    text = "${week.completedWorkouts}/${week.goal}",
                                    color = if (isCompleted) Lime400 else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
