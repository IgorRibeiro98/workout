package com.example.components.workout.execution

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun WorkoutActionButton(
    onClick: () -> Unit,
    text: String = "CONCLUIR SÉRIE",
    completedText: String = "SÉRIE CONCLUÍDA",
    enabled: Boolean = true,
    hapticEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isConfirming by remember { mutableStateOf(false) }

    val cleanText = remember(text) { text.removePrefix("✓ ").trim() }
    val cleanCompletedText = remember(completedText) { completedText.removePrefix("✓ ").trim() }

    val buttonScale by animateFloatAsState(
        targetValue = if (isConfirming) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "workout_button_scale"
    )

    Button(
        onClick = {
            if (!isConfirming) {
                isConfirming = true
                if (hapticEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        },
        enabled = enabled && !isConfirming,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isConfirming) Lime400 else Lime400,
            contentColor = BackgroundDark,
            disabledContainerColor = if (isConfirming) Lime400 else SurfaceDark,
            disabledContentColor = if (isConfirming) BackgroundDark else TextSecondary
        ),
        modifier = modifier
            .fillMaxWidth()
            // Minimum, not fixed: at large font scales the label must push the
            // button taller instead of being clipped inside it.
            .heightIn(min = 60.dp)
            .scale(buttonScale)
            .testTag("complete_set_button")
    ) {
        LaunchedEffect(isConfirming) {
            if (isConfirming) {
                delay(400)
                onClick()
                isConfirming = false
            }
        }

        AnimatedContent(
            targetState = isConfirming,
            transitionSpec = {
                (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut())
            },
            label = "button_icon_animation"
        ) { confirming ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (confirming) cleanCompletedText else cleanText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

