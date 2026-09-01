package com.example.feature.evolution.components.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BodyMeasurementEntity
import com.example.domain.evolution.model.BMICategory
import com.example.ui.theme.Lime400
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun BodyEvolutionSection(
    measurements: List<BodyMeasurementEntity>,
    currentWeight: Float?,
    initialWeight: Float?,
    weightVariation: Float?,
    currentHeight: Float?,
    bmi: Float?,
    bmiCategory: BMICategory? = null,
    onRegisterMeasurementClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("body_evolution_section"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccessibilityNew,
                    contentDescription = null,
                    tint = Lime400
                )
                Text(
                    text = "Evolução Corporal",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Acompanhe suas mudanças de peso, IMC e medidas ao longo do tempo",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // 1. Weight Card (Current Weight, Variation & Chart)
        WeightHistoryCard(
            currentWeight = currentWeight,
            initialWeight = initialWeight,
            weightVariation = weightVariation,
            measurements = measurements,
            onRegisterWeightClick = onRegisterMeasurementClick
        )

        // 2. BMI Card
        BmiCard(
            bmi = bmi,
            currentWeight = currentWeight,
            currentHeight = currentHeight,
            bmiCategory = bmiCategory,
            onAddHeightClick = onRegisterMeasurementClick
        )

        // 3. Body Measurements Comparison Card
        BodyMeasurementComparisonCard(
            measurements = measurements
        )
    }
}
