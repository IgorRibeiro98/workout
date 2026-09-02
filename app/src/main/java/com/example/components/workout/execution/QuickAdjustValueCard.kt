package com.example.components.workout.execution

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun QuickAdjustValueCard(
    label: String,
    value: Float,
    unit: String,
    step: Float,
    minValue: Float = 0f,
    maxValue: Float = 500f,
    isInteger: Boolean = false,
    hapticEnabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onDirectInputRequest: () -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "value_adjust"
) {
    val haptic = LocalHapticFeedback.current

    val displayValue = if (isInteger || value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(java.util.Locale("pt", "BR"), "%.1f", value)
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .testTag("${testTagPrefix}_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Label
            Text(
                text = label.uppercase(),
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            // Control Row: [-] [ VALUE ] [+]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Minus Button
                Surface(
                    onClick = {
                        val nextVal = (value - step).coerceIn(minValue, maxValue)
                        if (nextVal != value) {
                            if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onValueChange(nextVal)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = BackgroundDark,
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("${testTagPrefix}_minus")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Diminuir $label",
                            tint = if (value > minValue) TextPrimary else TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Center Value Clickable
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onDirectInputRequest)
                        .padding(vertical = 4.dp)
                        .testTag("${testTagPrefix}_value_display"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = displayValue,
                            color = Lime400,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                        if (unit.isNotBlank()) {
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = unit,
                                color = TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }
                    }
                }

                // Plus Button
                Surface(
                    onClick = {
                        val nextVal = (value + step).coerceIn(minValue, maxValue)
                        if (nextVal != value) {
                            if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onValueChange(nextVal)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = BackgroundDark,
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("${testTagPrefix}_plus")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Aumentar $label",
                            tint = if (value < maxValue) Lime400 else TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
