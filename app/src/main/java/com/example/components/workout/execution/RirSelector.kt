package com.example.components.workout.execution

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

data class RirOption(
    val rirValue: Float?,
    val label: String,
    val isFailure: Boolean = false
)

private val defaultRirOptions = listOf(
    RirOption(4f, "4+"),
    RirOption(3f, "3"),
    RirOption(2f, "2"),
    RirOption(1f, "1"),
    RirOption(0f, "🔥 Falha", isFailure = true)
)

@Composable
fun RirSelector(
    currentRir: Float?,
    onRirSelected: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    options: List<RirOption> = defaultRirOptions
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rir_selector"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RIR (REPETIÇÕES DE RESERVA)",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val isSelected = if (option.rirValue == null) {
                    currentRir == null
                } else if (option.rirValue == 4f) {
                    currentRir != null && currentRir >= 4f
                } else {
                    currentRir == option.rirValue
                }

                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else 1f,
                    animationSpec = spring(),
                    label = "rir_scale_${option.label}"
                )

                val backgroundColor by animateColorAsState(
                    targetValue = when {
                        isSelected && option.isFailure -> Red500.copy(alpha = 0.25f)
                        isSelected -> Lime400.copy(alpha = 0.2f)
                        else -> SurfaceDark
                    },
                    label = "rir_bg_${option.label}"
                )

                val borderColor by animateColorAsState(
                    targetValue = when {
                        isSelected && option.isFailure -> Red500
                        isSelected -> Lime400
                        else -> BorderLight
                    },
                    label = "rir_border_${option.label}"
                )

                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected && option.isFailure -> Red500
                        isSelected -> Lime400
                        else -> TextPrimary
                    },
                    label = "rir_text_${option.label}"
                )

                Surface(
                    color = backgroundColor,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
                    modifier = Modifier
                        .weight(1f)
                        .scale(scale)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onRirSelected(option.rirValue) }
                        .testTag("rir_option_${option.label}")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            color = textColor,
                            fontSize = if (option.isFailure) 12.sp else 14.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
