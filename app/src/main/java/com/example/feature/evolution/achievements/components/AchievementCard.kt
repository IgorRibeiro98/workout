package com.example.feature.evolution.achievements.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.achievement.Achievement
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AchievementCard(
    achievement: Achievement,
    modifier: Modifier = Modifier
) {
    val isUnlocked = achievement.unlockedAt != null
    val cardBg = if (isUnlocked) SurfaceHighlight else SurfaceDark
    val borderModifier = if (isUnlocked) {
        Modifier.border(1.dp, Lime400.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
    } else {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(borderModifier)
            .testTag("achievement_card_${achievement.id}"),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isUnlocked) Lime400.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.05f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isUnlocked) achievement.icon else "🔒",
                    fontSize = 22.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = achievement.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isUnlocked) TextPrimary else TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = achievement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isUnlocked) {
                    val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        .format(Date(achievement.unlockedAt!!))
                    Text(
                        text = "Concluído em: $dateStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lime400,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { achievement.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Lime400,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val unitLabel = when (achievement.id) {
                        "first_week", "30_days_consistent" -> "dias"
                        "load_evolution_10kg", "body_evolution_5kg" -> "kg"
                        else -> "treinos"
                    }

                    Text(
                        text = "${achievement.currentProgress}/${achievement.targetProgress} $unitLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}
