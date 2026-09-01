package com.example.feature.evolution.components.body

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.example.domain.evolution.model.BodyMeasurement
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.util.Locale

@Composable
fun WeightEvolutionCard(
    currentWeight: Float?,
    initialWeight: Float?,
    weightVariation: Float?,
    measurements: List<BodyMeasurement>,
    onRegisterWeightClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    WeightHistoryCard(
        currentWeight = currentWeight,
        initialWeight = initialWeight,
        weightVariation = weightVariation,
        measurements = measurements,
        onRegisterWeightClick = onRegisterWeightClick,
        modifier = modifier
    )
}

@Composable
fun WeightHistoryCard(
    currentWeight: Float?,
    initialWeight: Float?,
    weightVariation: Float?,
    measurements: List<BodyMeasurement>,
    onRegisterWeightClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("weight_history_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header
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
                        text = "Peso",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (onRegisterWeightClick != null) {
                    Surface(
                        color = SurfaceHighlight,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .testTag("btn_register_weight")
                            .clickable { onRegisterWeightClick() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Lime400,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Registrar",
                                color = Lime400,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (currentWeight == null || currentWeight <= 0f) {
                // Empty state
                Surface(
                    color = SurfaceHighlight,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Registre seu peso para acompanhar sua evolução",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (onRegisterWeightClick != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onRegisterWeightClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Lime400,
                                    contentColor = SurfaceDark
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Registrar peso", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                val weightFormatted = String.format(Locale.US, "%.1f", currentWeight).replace('.', ',')

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "Peso atual",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = weightFormatted,
                                color = TextPrimary,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("txt_current_weight")
                            )
                            Text(
                                text = "kg",
                                color = TextSecondary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (weightVariation != null && initialWeight != null && initialWeight > 0f) {
                        val variationSign = if (weightVariation > 0) "+" else ""
                        val variationFormatted = String.format(Locale.US, "%.1f", weightVariation).replace('.', ',')
                        val icon = when {
                            weightVariation < 0 -> Icons.Default.TrendingDown
                            weightVariation > 0 -> Icons.Default.TrendingUp
                            else -> Icons.Default.TrendingFlat
                        }

                        Column(
                            horizontalAlignment = Alignment.End
                        ) {
                            Surface(
                                color = SurfaceHighlight,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BorderLight)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = TextPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "$variationSign$variationFormatted kg",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.testTag("txt_weight_variation")
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Desde o primeiro registro",
                                color = TextTertiary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Chart
                WeightChart(
                    measurements = measurements
                )
            }
        }
    }
}
