package com.example.feature.evolution.consistency.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WorkoutCalendarCard(
    workoutTimestamps: List<Long> = emptyList(),
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault()
) {
    val localDates = workoutTimestamps.map {
        Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
    }.toSet()

    val referenceDate = if (localDates.isNotEmpty()) {
        localDates.maxOrNull() ?: LocalDate.now(zoneId)
    } else {
        LocalDate.now(zoneId)
    }

    val yearMonth = YearMonth.of(referenceDate.year, referenceDate.month)
    val monthName = yearMonth.month.getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
    val titleText = "$monthName ${yearMonth.year}"

    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOfWeek = yearMonth.atDay(1).dayOfWeek.value % 7 // 0 = Domingo, 1 = Segunda, ...

    val weekHeaderLabels = listOf("D", "S", "T", "Q", "Q", "S", "S")

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("workout_calendar_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Lime400.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = titleText,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Histórico de sessões do mês",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cabeçalho com dias da semana
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                weekHeaderLabels.forEach { dayLabel ->
                    Text(
                        text = dayLabel,
                        color = TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Grid de Dias do Mês
            val totalSlots = firstDayOfWeek + daysInMonth
            val rows = (totalSlots + 6) / 7

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (rowIndex in 0 until rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        for (colIndex in 0..6) {
                            val slotIndex = rowIndex * 7 + colIndex
                            val dayNumber = slotIndex - firstDayOfWeek + 1

                            if (dayNumber in 1..daysInMonth) {
                                val currentDate = yearMonth.atDay(dayNumber)
                                val hasWorkout = localDates.contains(currentDate)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (hasWorkout) LimeTransparent else SurfaceHighlight
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = String.format(Locale.getDefault(), "%02d", dayNumber),
                                            color = if (hasWorkout) Lime400 else TextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (hasWorkout) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (hasWorkout) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Treinado",
                                                tint = Lime400,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
