package com.example.feature.evolution.achievements.components

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
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.achievement.AchievementTier
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun getTierColor(tier: AchievementTier): Color {
    return when (tier) {
        AchievementTier.BRONZE -> Color(0xFFCD7F32)
        AchievementTier.SILVER -> Color(0xFFC0C0C0)
        AchievementTier.GOLD -> Color(0xFFFFD700)
        AchievementTier.PLATINUM -> Color(0xFFE5E4E2)
    }
}

fun getTierName(tier: AchievementTier): String {
    return when (tier) {
        AchievementTier.BRONZE -> "Bronze"
        AchievementTier.SILVER -> "Prata"
        AchievementTier.GOLD -> "Ouro"
        AchievementTier.PLATINUM -> "Platina"
    }
}

@Composable
fun AchievementCard(
    achievement: Achievement,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val isUnlocked = achievement.unlockedAt != null
    val cardBg = if (isUnlocked) SurfaceHighlight else SurfaceDark
    val tierColor = getTierColor(achievement.tier)
    
    val borderModifier = if (isUnlocked) {
        Modifier.border(1.dp, tierColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
    } else {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(borderModifier)
            .testTag("achievement_card_${achievement.id}"),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        onClick = { onClick?.invoke() },
        enabled = onClick != null
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
                        if (isUnlocked) tierColor.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .border(1.dp, if (isUnlocked) tierColor else Color.Transparent, CircleShape),
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
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = achievement.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isUnlocked) TextPrimary else TextSecondary,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Explicit Tier Badge (RN-04 / Section 9.4: sem depender apenas de cor)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tierColor.copy(alpha = if (isUnlocked) 0.2f else 0.08f))
                            .border(1.dp, tierColor.copy(alpha = if (isUnlocked) 0.6f else 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = getTierName(achievement.tier),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUnlocked) tierColor else tierColor.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
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
                        text = "Desbloqueada: $dateStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { achievement.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = tierColor,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val unitLabel = when (achievement.category) {
                        AchievementCategory.TRAINING -> if (achievement.targetProgress == 1) "treino" else "treinos"
                        AchievementCategory.CONSISTENCY -> if (achievement.targetProgress == 1) "semana" else "semanas"
                        AchievementCategory.PERFORMANCE -> if (achievement.targetProgress == 1) "recorde" else "recordes"
                        AchievementCategory.BODY -> if (achievement.targetProgress == 1) "medição" else "medições"
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
