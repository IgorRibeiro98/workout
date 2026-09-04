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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
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
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.ui.theme.Amber500
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Orange400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun StreakCard(
    summary: WorkoutConsistencySummary?,
    progress: ConsistencyProgress? = null,
    currentGoal: Int = progress?.currentWeekGoal ?: 3,
    modifier: Modifier = Modifier
) {
    val currentStreak = progress?.currentStreakWeeks ?: summary?.currentStreak ?: 0
    val longestStreak = progress?.longestStreakWeeks ?: summary?.longestStreak ?: 0

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("streak_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Amber500.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WorkspacePremium,
                            contentDescription = null,
                            tint = Amber500,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "Consistência Semanal",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = SurfaceHighlight,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Text(
                        text = "Meta: $currentGoal ${if (currentGoal == 1) "treino" else "treinos"}/sem",
                        color = Lime400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Current Streak
                Surface(
                    color = SurfaceHighlight,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("streak_card_current")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Text(
                            text = "Sequência atual",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🔥 $currentStreak ${if (currentStreak == 1) "semana" else "semanas"}",
                            color = Orange400,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Longest Streak
                Surface(
                    color = SurfaceHighlight,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("streak_card_longest")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Text(
                            text = "Maior sequência",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🏆 $longestStreak ${if (longestStreak == 1) "semana" else "semanas"}",
                            color = Amber500,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (currentStreak > 0) {
                    "Você manteve sua meta semanal por $currentStreak ${if (currentStreak == 1) "semana consecutiva" else "semanas consecutivas"}!"
                } else if (longestStreak > 0) {
                    "Maior sequência histórica: $longestStreak ${if (longestStreak == 1) "semana" else "semanas"}. Cumpra sua meta esta semana para iniciar uma nova sequência!"
                } else {
                    "Cumpra sua meta de treinos nesta semana para começar sua sequência de consistência."
                },
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
