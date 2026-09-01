package com.example.ui.components.body

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.TrendingUp
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
import com.example.data.local.BodyMeasurementEntity
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvolutionSummaryCard(
    measurement: BodyMeasurementEntity,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val formattedDate = dateFormatter.format(Date(measurement.date))

    val secondaryItems = buildList {
        measurement.waistCm?.let { add(Triple("Cintura", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.bodyFatPercentage?.let { add(Triple("Gordura", String.format(Locale.getDefault(), "%.1f", it), "%")) }
        measurement.abdomenCm?.let { add(Triple("Abdômen", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.chestCm?.let { add(Triple("Peito", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.rightArmCm?.let { add(Triple("Braço D.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.leftArmCm?.let { add(Triple("Braço E.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.rightThighCm?.let { add(Triple("Coxa D.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.leftThighCm?.let { add(Triple("Coxa E.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.calfCm?.let { add(Triple("Panturrilha", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.hipCm?.let { add(Triple("Quadril", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Lime400.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("evolution_summary_card")
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(LimeTransparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "Última evolução",
                        color = Lime400,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formattedDate,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    if (onClick != null) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Highlight: Peso (or Waist if no weight)
            if (measurement.weightKg != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "Peso",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.getDefault(), "%.1f", measurement.weightKg),
                                color = TextPrimary,
                                fontWeight = FontWeight.Black,
                                fontSize = 32.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "kg",
                                color = Lime400,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }

                    if (measurement.waistCm != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Cintura",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = String.format(Locale.getDefault(), "%.1f", measurement.waistCm),
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "cm",
                                    color = Lime400,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }
                    }
                }
            } else if (secondaryItems.isNotEmpty()) {
                val first = secondaryItems.first()
                Column {
                    Text(
                        text = first.first,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = first.second,
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = first.third,
                            color = Lime400,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }

            // Other measurements as chips/badges if present
            val remainingItems = if (measurement.weightKg != null && measurement.waistCm != null) {
                secondaryItems.filter { it.first != "Cintura" }
            } else if (measurement.weightKg != null) {
                secondaryItems
            } else {
                secondaryItems.drop(1)
            }

            if (remainingItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    remainingItems.forEach { (label, value, unit) ->
                        MeasurementBadge(label = label, value = value, unit = unit)
                    }
                }
            }
        }
    }
}
