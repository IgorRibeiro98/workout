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
import com.example.domain.evolution.model.BodyMeasurement
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary

data class MetricComparisonData(
    val label: String,
    val initialValue: Float?,
    val currentValue: Float,
    val unit: String = "cm"
)

@Composable
fun BodyComparisonCard(
    measurements: List<BodyMeasurement>,
    modifier: Modifier = Modifier
) {
    val sorted = measurements.sortedBy { it.date }
    val latest = sorted.lastOrNull() ?: return

    val items = mutableListOf<MetricComparisonData>()

    // Peso
    if (latest.weightKg != null && latest.weightKg > 0f) {
        val initial = sorted.firstOrNull { it.weightKg != null && it.weightKg > 0f }?.weightKg
        items.add(MetricComparisonData("Peso", initial, latest.weightKg, unit = "kg"))
    }

    // Cintura
    if (latest.waistCm != null && latest.waistCm > 0f) {
        val initial = sorted.firstOrNull { it.waistCm != null && it.waistCm > 0f }?.waistCm
        items.add(MetricComparisonData("Cintura", initial, latest.waistCm))
    }

    // Abdômen
    if (latest.abdomenCm != null && latest.abdomenCm > 0f) {
        val initial = sorted.firstOrNull { it.abdomenCm != null && it.abdomenCm > 0f }?.abdomenCm
        items.add(MetricComparisonData("Abdômen", initial, latest.abdomenCm))
    }

    // Peitoral
    if (latest.chestCm != null && latest.chestCm > 0f) {
        val initial = sorted.firstOrNull { it.chestCm != null && it.chestCm > 0f }?.chestCm
        items.add(MetricComparisonData("Peitoral", initial, latest.chestCm))
    }

    // Braço direito
    if (latest.rightArmCm != null && latest.rightArmCm > 0f) {
        val initial = sorted.firstOrNull { it.rightArmCm != null && it.rightArmCm > 0f }?.rightArmCm
        items.add(MetricComparisonData("Braço direito", initial, latest.rightArmCm))
    }

    // Braço esquerdo
    if (latest.leftArmCm != null && latest.leftArmCm > 0f) {
        val initial = sorted.firstOrNull { it.leftArmCm != null && it.leftArmCm > 0f }?.leftArmCm
        items.add(MetricComparisonData("Braço esquerdo", initial, latest.leftArmCm))
    }

    // Quadril
    if (latest.hipCm != null && latest.hipCm > 0f) {
        val initial = sorted.firstOrNull { it.hipCm != null && it.hipCm > 0f }?.hipCm
        items.add(MetricComparisonData("Quadril", initial, latest.hipCm))
    }

    // Coxa direita
    if (latest.rightThighCm != null && latest.rightThighCm > 0f) {
        val initial = sorted.firstOrNull { it.rightThighCm != null && it.rightThighCm > 0f }?.rightThighCm
        items.add(MetricComparisonData("Coxa direita", initial, latest.rightThighCm))
    }

    // Coxa esquerda
    if (latest.leftThighCm != null && latest.leftThighCm > 0f) {
        val initial = sorted.firstOrNull { it.leftThighCm != null && it.leftThighCm > 0f }?.leftThighCm
        items.add(MetricComparisonData("Coxa esquerda", initial, latest.leftThighCm))
    }

    // Panturrilha direita
    if (latest.rightCalfCm != null && latest.rightCalfCm > 0f) {
        val initial = sorted.firstOrNull { it.rightCalfCm != null && it.rightCalfCm > 0f }?.rightCalfCm
        items.add(MetricComparisonData("Panturrilha direita", initial, latest.rightCalfCm))
    }

    // Panturrilha esquerda
    if (latest.leftCalfCm != null && latest.leftCalfCm > 0f && latest.leftCalfCm != latest.rightCalfCm) {
        val initial = sorted.firstOrNull { it.leftCalfCm != null && it.leftCalfCm > 0f }?.leftCalfCm
        items.add(MetricComparisonData("Panturrilha esquerda", initial, latest.leftCalfCm))
    }

    // % Gordura
    if (latest.bodyFatPercentage != null && latest.bodyFatPercentage > 0f) {
        val initial = sorted.firstOrNull { it.bodyFatPercentage != null && it.bodyFatPercentage > 0f }?.bodyFatPercentage
        items.add(MetricComparisonData("% Gordura", initial, latest.bodyFatPercentage, unit = "%"))
    }

    if (items.isEmpty()) return

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("body_comparison_card")
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
                        text = "Sua evolução",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { item ->
                    BodyMetricComparisonRow(
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
