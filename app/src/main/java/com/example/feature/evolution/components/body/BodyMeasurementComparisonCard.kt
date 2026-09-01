package com.example.feature.evolution.components.body

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
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

data class MeasuredItem(
    val label: String,
    val initialValue: Float?,
    val currentValue: Float,
    val unit: String = "cm"
)

@Composable
fun BodyMeasurementComparisonCard(
    measurements: List<BodyMeasurementEntity>,
    modifier: Modifier = Modifier
) {
    val sorted = measurements.sortedWith(compareBy({ it.date }, { it.createdAt }))
    val latest = sorted.lastOrNull()
    val first = sorted.firstOrNull()

    if (latest == null) return

    val items = mutableListOf<MeasuredItem>()

    // Waist / Cintura
    if (latest.waistCm != null && latest.waistCm > 0f) {
        val initial = sorted.firstOrNull { it.waistCm != null && it.waistCm > 0f }?.waistCm
        items.add(MeasuredItem("Cintura", initial, latest.waistCm))
    }

    // Abdomen / Abdômen
    if (latest.abdomenCm != null && latest.abdomenCm > 0f) {
        val initial = sorted.firstOrNull { it.abdomenCm != null && it.abdomenCm > 0f }?.abdomenCm
        items.add(MeasuredItem("Abdômen", initial, latest.abdomenCm))
    }

    // Chest / Peitoral
    if (latest.chestCm != null && latest.chestCm > 0f) {
        val initial = sorted.firstOrNull { it.chestCm != null && it.chestCm > 0f }?.chestCm
        items.add(MeasuredItem("Peito", initial, latest.chestCm))
    }

    // Right Arm / Braço direito
    if (latest.rightArmCm != null && latest.rightArmCm > 0f) {
        val initial = sorted.firstOrNull { it.rightArmCm != null && it.rightArmCm > 0f }?.rightArmCm
        items.add(MeasuredItem("Braço direito", initial, latest.rightArmCm))
    }

    // Left Arm / Braço esquerdo
    if (latest.leftArmCm != null && latest.leftArmCm > 0f) {
        val initial = sorted.firstOrNull { it.leftArmCm != null && it.leftArmCm > 0f }?.leftArmCm
        items.add(MeasuredItem("Braço esquerdo", initial, latest.leftArmCm))
    }

    // Hip / Quadril
    if (latest.hipCm != null && latest.hipCm > 0f) {
        val initial = sorted.firstOrNull { it.hipCm != null && it.hipCm > 0f }?.hipCm
        items.add(MeasuredItem("Quadril", initial, latest.hipCm))
    }

    // Right Thigh / Coxa direita
    if (latest.rightThighCm != null && latest.rightThighCm > 0f) {
        val initial = sorted.firstOrNull { it.rightThighCm != null && it.rightThighCm > 0f }?.rightThighCm
        items.add(MeasuredItem("Coxa direita", initial, latest.rightThighCm))
    }

    // Left Thigh / Coxa esquerda
    if (latest.leftThighCm != null && latest.leftThighCm > 0f) {
        val initial = sorted.firstOrNull { it.leftThighCm != null && it.leftThighCm > 0f }?.leftThighCm
        items.add(MeasuredItem("Coxa esquerda", initial, latest.leftThighCm))
    }

    // Calf / Panturrilha
    if (latest.calfCm != null && latest.calfCm > 0f) {
        val initial = sorted.firstOrNull { it.calfCm != null && it.calfCm > 0f }?.calfCm
        items.add(MeasuredItem("Panturrilha", initial, latest.calfCm))
    }

    // Fat % / % Gordura
    if (latest.bodyFatPercentage != null && latest.bodyFatPercentage > 0f) {
        val initial = sorted.firstOrNull { it.bodyFatPercentage != null && it.bodyFatPercentage > 0f }?.bodyFatPercentage
        items.add(MeasuredItem("% Gordura", initial, latest.bodyFatPercentage, unit = "%"))
    }

    // If no body circumference measurements exist, don't show an empty list
    if (items.isEmpty()) return

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("body_measurement_comparison_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(LimeTransparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Straighten,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "Medidas Corporais",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEach { item ->
                    BodyMetricSummaryCard(
                        label = item.label,
                        initialValue = item.initialValue,
                        currentValue = item.currentValue,
                        unit = item.unit
                    )
                }
            }
        }
    }
}
