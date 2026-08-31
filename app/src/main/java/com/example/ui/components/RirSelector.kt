package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.engine.RirFormatter
import com.example.ui.theme.*

data class RirOption(
    val value: Int,
    val displayLabel: String,
    val isFailure: Boolean = false
)

@Composable
fun RirSelector(
    currentRir: Int?,
    onRirSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        RirOption(4, "4+"),
        RirOption(3, "3"),
        RirOption(2, "2"),
        RirOption(1, "1"),
        RirOption(0, "🔥 FALHA", isFailure = true)
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.rir_effort_label),
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            if (currentRir == 0) {
                Text(
                    text = stringResource(id = R.string.rir_failure_full),
                    color = Color(0xFFFFB74D),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            } else if (currentRir != null) {
                Text(
                    text = RirFormatter.formatRir(currentRir) ?: "",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val isSelected = when {
                    option.value == 4 -> currentRir != null && currentRir >= 4
                    else -> currentRir == option.value
                }

                val accessibilityDescription = if (option.isFailure) {
                    if (isSelected) stringResource(id = R.string.rir_failure_accessibility) + ", selecionado"
                    else stringResource(id = R.string.rir_failure_accessibility)
                } else {
                    if (isSelected) "RIR ${option.displayLabel}, selecionado"
                    else "RIR ${option.displayLabel}"
                }

                val targetBgColor = when {
                    isSelected && option.isFailure -> Color(0xFFFF9800).copy(alpha = 0.25f)
                    isSelected -> Lime400.copy(alpha = 0.2f)
                    else -> SurfaceDark
                }

                val targetBorderColor = when {
                    isSelected && option.isFailure -> Color(0xFFFF9800)
                    isSelected -> Lime400
                    else -> BorderLight
                }

                val targetTextColor = when {
                    isSelected && option.isFailure -> Color(0xFFFFB74D)
                    isSelected -> Lime400
                    option.isFailure -> TextPrimary
                    else -> TextSecondary
                }

                val containerColor by animateColorAsState(targetBgColor, label = "rirBgColor")
                val borderColor by animateColorAsState(targetBorderColor, label = "rirBorderColor")
                val textColor by animateColorAsState(targetTextColor, label = "rirTextColor")

                val optionScale by animateFloatAsState(
                    targetValue = if (isSelected && option.isFailure) 1.15f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
                    label = "rirOptionScale"
                )

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer(scaleX = optionScale, scaleY = optionScale)
                        .semantics { contentDescription = accessibilityDescription }
                        .clickable {
                            if (isSelected) {
                                onRirSelected(null)
                            } else {
                                onRirSelected(option.value)
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = containerColor,
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = if (isSelected && option.isFailure) "🔥 FALHA" else option.displayLabel,
                            color = textColor,
                            fontSize = if (option.isFailure) 11.sp else 14.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
