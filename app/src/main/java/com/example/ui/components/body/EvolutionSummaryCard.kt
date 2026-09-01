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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BodyMeasurementEntity
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.Orange400
import com.example.ui.theme.Red400
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
    weightVariationFromStart: Float? = null,
    waistVariationFromStart: Float? = null,
    onClick: (() -> Unit)? = null
) {
    val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val formattedDate = dateFormatter.format(Date(measurement.date))

    val secondaryItems = buildList {
        measurement.abdomenCm?.let { add(Triple("Abdômen", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.chestCm?.let { add(Triple("Peito", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.bodyFatPercentage?.let { add(Triple("Gordura", String.format(Locale.getDefault(), "%.1f", it), "%")) }
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
        border = androidx.compose.foundation.BorderStroke(1.dp, Lime400.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("evolution_summary_card")
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header: Title & Date
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BorderLight.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = formattedDate,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("summary_latest_date")
                    )
                    if (onClick != null) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Primary Highlight: Peso
            if (measurement.weightKg != null) {
                Column {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = String.format(Locale.getDefault(), "%.1f", measurement.weightKg).replace('.', ','),
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 36.sp,
                            modifier = Modifier.testTag("summary_weight_value")
                        )
                        Text(
                            text = "kg",
                            color = Lime400,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    // Weight variation since the start (if multiple entries)
                    if (weightVariationFromStart != null) {
                        val isNegative = weightVariationFromStart < -0.05f
                        val isPositive = weightVariationFromStart > 0.05f
                        val sign = if (isPositive) "+" else ""
                        val formattedDiff = "$sign${String.format(Locale.getDefault(), "%.1f", weightVariationFromStart).replace('.', ',')} kg desde o início"

                        val diffColor = if (isNegative) Lime400 else if (isPositive) Orange400 else TextSecondary

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(
                                imageVector = if (isNegative) Icons.Default.TrendingDown else if (isPositive) Icons.Default.TrendingUp else Icons.Default.TrendingFlat,
                                contentDescription = null,
                                tint = diffColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = formattedDiff,
                                color = diffColor,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                modifier = Modifier.testTag("summary_weight_variation")
                            )
                        }
                    }
                }
            }

            // Cintura Section (if present)
            if (measurement.waistCm != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BorderLight.copy(alpha = 0.3f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Cintura",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.getDefault(), "%.1f", measurement.waistCm).replace('.', ','),
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    modifier = Modifier.testTag("summary_waist_value")
                                )
                                Text(
                                    text = "cm",
                                    color = Lime400,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }

                        if (waistVariationFromStart != null) {
                            val isNegative = waistVariationFromStart < -0.05f
                            val isPositive = waistVariationFromStart > 0.05f
                            val sign = if (isPositive) "+" else ""
                            val formattedDiff = "$sign${String.format(Locale.getDefault(), "%.1f", waistVariationFromStart).replace('.', ',')} cm"
                            val diffColor = if (isNegative) Lime400 else if (isPositive) Orange400 else TextSecondary

                            Text(
                                text = "$formattedDiff desde o início",
                                color = diffColor,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Other secondary measurements (e.g. Peito, Braço, Abdômen)
            if (secondaryItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    secondaryItems.forEach { (label, value, unit) ->
                        SummaryMiniBadge(label = label, value = value, unit = unit)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMiniBadge(
    label: String,
    value: String,
    unit: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BorderLight.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$label:",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value.replace('.', ','),
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = unit,
                color = Lime400,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
