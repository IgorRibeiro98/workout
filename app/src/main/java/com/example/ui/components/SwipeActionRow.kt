package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Representa uma ação de deslize (swipe) contextual.
 */
data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val backgroundColor: Color,
    val contentColor: Color,
    val onTrigger: () -> Unit
)

/**
 * Um container de swipe reutilizável que envolve qualquer item de lista (card, row)
 * e fornece gestos rápidos para a direita (START -> END) e esquerda (END -> START).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionRow(
    modifier: Modifier = Modifier,
    startAction: SwipeAction? = null,
    endAction: SwipeAction? = null,
    enabled: Boolean = true,
    hapticEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled || (startAction == null && endAction == null)) {
        Box(modifier = modifier) {
            content()
        }
        return
    }

    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (startAction != null) {
                        startAction.onTrigger()
                    }
                    false // Retorna false para que o item volte à posição original
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (endAction != null) {
                        endAction.onTrigger()
                    }
                    false // Retorna false para que o item volte à posição original (ex: abre confirmação ou executa)
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    // Feedback hático apenas quando atinge o threshold (entra no estado de trigger)
    val targetValue = dismissState.targetValue
    LaunchedEffect(targetValue) {
        if (hapticEnabled && targetValue != SwipeToDismissBoxValue.Settled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = startAction != null,
        enableDismissFromEndToStart = endAction != null,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                else -> null
            }

            val color by animateColorAsState(
                targetValue = action?.backgroundColor ?: Color.Transparent,
                label = "swipeBgColor"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                }
            ) {
                if (action != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics {
                            contentDescription = action.label
                        }
                    ) {
                        if (direction == SwipeToDismissBoxValue.StartToEnd) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = action.contentColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = action.label,
                                color = action.contentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                text = action.label,
                                color = action.contentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = action.contentColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}
