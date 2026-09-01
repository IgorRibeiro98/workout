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
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

@Composable
fun VolumeSummaryCard(
    totalVolumeKg: Float,
    modifier: Modifier = Modifier,
    testTag: String = "volume_summary_card"
) {
    val formattedVolume = remember(totalVolumeKg) {
        formatVolumeSummary(totalVolumeKg)
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
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Volume treinado",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Carga total acumulada",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Highlighted big number
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formattedVolume,
                    color = Lime400,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.testTag("volume_summary_highlight_text")
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "em toda sua jornada",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

fun formatVolumeSummary(volumeKg: Float): String {
    return when {
        volumeKg >= 1_000_000f -> {
            val millions = volumeKg / 1_000_000f
            val formatted = if (millions % 1.0f == 0f) {
                String.format(Locale("pt", "BR"), "%.0f", millions)
            } else {
                String.format(Locale("pt", "BR"), "%.1f", millions)
            }
            "$formatted mi kg"
        }
        volumeKg >= 1_000f -> {
            val thousands = volumeKg / 1_000f
            val formatted = if (thousands % 1.0f == 0f) {
                String.format(Locale("pt", "BR"), "%.0f", thousands)
            } else {
                String.format(Locale("pt", "BR"), "%.1f", thousands)
            }
            "$formatted mil kg"
        }
        else -> {
            val formatted = if (volumeKg % 1.0f == 0f) {
                String.format(Locale("pt", "BR"), "%.0f", volumeKg)
            } else {
                String.format(Locale("pt", "BR"), "%.1f", volumeKg)
            }
            "$formatted kg"
        }
    }
}
