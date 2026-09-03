package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.RirFormatter
import com.example.ui.theme.*

data class RirOption(
    val value: Int,
    val emoji: String,
    val displayLabel: String,
    val rirLabel: String,
    val isFailure: Boolean = false
)

@Composable
fun RirSelector(
    currentRir: Int?,
    onRirSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = remember {
        listOf(
            RirOption(value = 0, emoji = "🔥", displayLabel = "Falha", rirLabel = "RIR 0", isFailure = true),
            RirOption(value = 1, emoji = "😤", displayLabel = "M. pesado", rirLabel = "RIR 1"),
            RirOption(value = 2, emoji = "💪", displayLabel = "Pesado", rirLabel = "RIR 2"),
            RirOption(value = 3, emoji = "🙂", displayLabel = "Controlado", rirLabel = "RIR 3+")
        )
    }

    val selectedIndex = when {
        currentRir == null -> -1
        currentRir == 0 -> 0
        currentRir == 1 -> 1
        currentRir == 2 -> 2
        else -> 3
    }

    var showHelpDialog by remember { mutableStateOf(false) }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "O que é RIR? (Repetições em Reserva)",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "RIR indica quantas repetições a mais você conseguiria fazer antes de falhar concentricamente com boa postura.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Surface(
                        color = BackgroundDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderLight),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "🔥 0 — Falha:\nNão aguentava mais nenhuma repetição.",
                                color = Color(0xFFFFB74D),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "😤 1 — Muito pesado:\nAguentaria apenas mais 1 repetição.",
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "💪 2 — Pesado:\nAguentaria mais 2 repetições com boa técnica.",
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "🙂 3+ — Controlado:\nAguentaria 3 ou mais (aquecimento ou reserva alta).",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showHelpDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Entendi", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showHelpDialog = true }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = "ESFORÇO / RIR",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "O que é RIR?",
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }

            if (currentRir == 0) {
                Text(
                    text = "🔥 Até a falha (RIR 0)",
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            } else if (currentRir != null) {
                val effortName = RirFormatter.formatEffort(currentRir) ?: ""
                val secRir = RirFormatter.formatSecondaryRir(currentRir)
                Text(
                    text = "$effortName ($secRir)",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 4 options fitting 100% of the screen width with no horizontal scroll
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex

                val targetBgColor = when {
                    isSelected && option.isFailure -> Color(0xFFFF9800).copy(alpha = 0.28f)
                    isSelected -> Lime400.copy(alpha = 0.25f)
                    selectedIndex >= 0 -> SurfaceDark.copy(alpha = 0.4f)
                    else -> SurfaceDark
                }
                val targetBorderColor = when {
                    isSelected && option.isFailure -> Color(0xFFFF9800)
                    isSelected -> Lime400
                    selectedIndex >= 0 -> BorderLight.copy(alpha = 0.3f)
                    else -> BorderLight
                }
                val targetTextColor = when {
                    isSelected && option.isFailure -> Color(0xFFFFB74D)
                    isSelected -> Lime400
                    selectedIndex >= 0 -> TextSecondary.copy(alpha = 0.5f)
                    option.isFailure -> TextPrimary
                    else -> TextSecondary
                }

                val containerColor by animateColorAsState(targetBgColor, label = "rirBgColor_$index")
                val borderColor by animateColorAsState(targetBorderColor, label = "rirBorderColor_$index")
                val textColor by animateColorAsState(targetTextColor, label = "rirTextColor_$index")

                val optionScale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else if (selectedIndex >= 0) 0.98f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
                    label = "rirOptionScale_$index"
                )

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .graphicsLayer(scaleX = optionScale, scaleY = optionScale)
                        .semantics {
                            contentDescription = "${option.emoji} ${option.displayLabel}, ${option.rirLabel}${if (isSelected) ", selecionado" else ""}"
                        }
                        .clickable {
                            onRirSelected(if (isSelected) null else option.value)
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = containerColor,
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                    ) {
                        Text(
                            text = "${option.emoji} ${option.displayLabel}",
                            color = textColor,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                        Text(
                            text = option.rirLabel,
                            color = if (isSelected) textColor.copy(alpha = 0.9f) else if (selectedIndex >= 0) TextSecondary.copy(alpha = 0.4f) else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

