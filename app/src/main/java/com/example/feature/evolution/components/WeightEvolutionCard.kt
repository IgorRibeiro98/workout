package com.example.feature.evolution.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.EvolutionSummary
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale
import kotlin.math.abs

@Composable
fun WeightEvolutionCard(
    summary: EvolutionSummary?,
    modifier: Modifier = Modifier,
    onRegisterWeightClick: (() -> Unit)? = null
) {
    val currentWeight = summary?.currentWeight
    val initialWeight = summary?.initialWeight
    val weightChange = summary?.weightChange

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("weight_evolution_card")
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
                            .background(LimeTransparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MonitorWeight,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "Peso atual",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (initialWeight != null && initialWeight > 0f) {
                    Text(
                        text = "Início: ${formatWeight(initialWeight)}",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (currentWeight != null && currentWeight > 0f) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = formatWeight(currentWeight),
                        color = TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("current_weight_text")
                    )

                    if (weightChange != null) {
                        val changeAbs = abs(weightChange)
                        val formattedChange = String.format(Locale.US, "%.1f", changeAbs).replace('.', ',')
                        val isLoss = weightChange < -0.05f
                        val isGain = weightChange > 0.05f

                        val (badgeIcon, badgeColor, badgeText) = when {
                            isLoss -> Triple(Icons.Default.ArrowDownward, Emerald500, "↓ $formattedChange kg")
                            isGain -> Triple(Icons.Default.ArrowUpward, Lime400, "↑ $formattedChange kg")
                            else -> Triple(Icons.Default.Remove, TextSecondary, "0,0 kg")
                        }

                        Column(
                            horizontalAlignment = Alignment.End
                        ) {
                            Surface(
                                color = badgeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("weight_variation_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = badgeIcon,
                                        contentDescription = null,
                                        tint = badgeColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "$formattedChange kg",
                                        color = badgeColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "desde o início",
                                color = TextTertiary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "Registre seu peso para acompanhar sua evolução",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

private fun formatWeight(weight: Float): String {
    val formatted = String.format(Locale.US, "%.1f", weight).replace('.', ',')
    return "$formatted kg"
}
