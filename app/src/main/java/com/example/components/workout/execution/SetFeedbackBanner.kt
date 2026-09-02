package com.example.components.workout.execution

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.presentation.execution.FeedbackType
import com.example.presentation.execution.SetCompletionFeedback
import com.example.ui.theme.*

@Composable
fun SetFeedbackBanner(
    feedback: SetCompletionFeedback?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = feedback != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier
    ) {
        if (feedback != null) {
            val (bgColor, borderColor, iconTint, icon) = when (feedback.type) {
                FeedbackType.NEW_RECORD -> Quadruple(
                    Color(0xFF2A1F08),
                    Color(0xFFFFB74D),
                    Color(0xFFFFB74D),
                    Icons.Default.LocalFireDepartment
                )
                FeedbackType.PROGRESSION -> Quadruple(
                    Color(0xFF0D2818),
                    Emerald500,
                    Emerald500,
                    Icons.Default.EmojiEvents
                )
                FeedbackType.GOAL_ACHIEVED -> Quadruple(
                    Color(0xFF0F2414),
                    Lime400,
                    Lime400,
                    Icons.Default.CheckCircle
                )
                FeedbackType.FIRST_TIME -> Quadruple(
                    Color(0xFF0F1A24),
                    Color(0xFF64B5F6),
                    Color(0xFF64B5F6),
                    Icons.Default.CheckCircle
                )
                FeedbackType.NORMAL -> Quadruple(
                    SurfaceDark,
                    BorderLight,
                    Lime400,
                    Icons.Default.CheckCircle
                )
            }

            Surface(
                color = bgColor,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .testTag("set_feedback_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = feedback.title,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!feedback.subtitle.isNullOrBlank()) {
                            Text(
                                text = feedback.subtitle,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar feedback",
                        tint = TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
