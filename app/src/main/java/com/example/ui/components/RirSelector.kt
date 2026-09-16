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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.ui.graphics.vector.ImageVector

data class RirOption(
    val value: Int,
    val icon: ImageVector,
    val displayLabel: String,
    val rirLabel: String,
    val isFailure: Boolean = false
)

/**
 * O ícone de esforço de um RIR (T19.7B) — a única tabela, usada pelo seletor, pelo resumo da
 * série e pela ajuda.
 *
 * A metáfora é reserva: RIR 0 é a falha (fogo, a linguagem que o app já usava), e de 1 em diante
 * o que resta na "bateria" cresce — 1 barra, 3 barras, cheia para 3+.
 */
fun rirEffortIcon(rir: Int?): ImageVector = when {
    rir == null -> Icons.Filled.BatteryUnknown
    rir <= 0 -> Icons.Filled.LocalFireDepartment
    rir == 1 -> Icons.Filled.Battery1Bar
    rir == 2 -> Icons.Filled.Battery3Bar
    else -> Icons.Filled.BatteryFull
}

@Composable
fun RirSelector(
    currentRir: Int?,
    onRirSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = remember {
        listOf(
            RirOption(value = 0, icon = rirEffortIcon(0), displayLabel = "Falha", rirLabel = "RIR 0", isFailure = true),
            RirOption(value = 1, icon = rirEffortIcon(1), displayLabel = "M. pesado", rirLabel = "RIR 1"),
            RirOption(value = 2, icon = rirEffortIcon(2), displayLabel = "Pesado", rirLabel = "RIR 2"),
            RirOption(value = 3, icon = rirEffortIcon(3), displayLabel = "Controlado", rirLabel = "RIR 3+")
        )
    }

    val selectedIndex = when {
        currentRir == null -> -1
        currentRir == 0 -> 0
        currentRir == 1 -> 1
        currentRir == 2 -> 2
        else -> 3
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
            RirHelpLabel(text = "ESFORÇO / RIR")

            if (currentRir == 0) {
                IconLabel(
                    icon = rirEffortIcon(0),
                    text = "Até a falha (RIR 0)",
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            } else if (currentRir != null) {
                val effortName = RirFormatter.formatEffort(currentRir) ?: ""
                val secRir = RirFormatter.formatSecondaryRir(currentRir)
                IconLabel(
                    icon = rirEffortIcon(currentRir),
                    text = "$effortName ($secRir)",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            text = "Repetições em Reserva: quantas reps você ainda aguentaria antes da falha.",
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 11.sp
        )

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
                            contentDescription = "${option.displayLabel}, ${option.rirLabel}${if (isSelected) ", selecionado" else ""}"
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
                        IconLabel(
                            icon = option.icon,
                            text = option.displayLabel,
                            color = textColor,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                            iconSize = 14.dp,
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

