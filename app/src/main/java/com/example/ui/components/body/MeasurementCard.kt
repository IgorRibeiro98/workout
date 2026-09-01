package com.example.ui.components.body

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.domain.body.BodyMetricsCalculator
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
fun MeasurementCard(
    measurement: BodyMeasurementEntity,
    modifier: Modifier = Modifier,
    onEditClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val formattedDate = dateFormatter.format(Date(measurement.date))

    val items = buildList {
        measurement.weightKg?.let { add(Triple("Peso", String.format(Locale.getDefault(), "%.1f", it), "kg")) }
        measurement.heightCm?.let { add(Triple("Altura", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.bodyFatPercentage?.let { add(Triple("Gordura", String.format(Locale.getDefault(), "%.1f", it), "%")) }
        measurement.waistCm?.let { add(Triple("Cintura", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.abdomenCm?.let { add(Triple("Abdômen", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.chestCm?.let { add(Triple("Peito", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.rightArmCm?.let { add(Triple("Braço D.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.leftArmCm?.let { add(Triple("Braço E.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.rightThighCm?.let { add(Triple("Coxa D.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.leftThighCm?.let { add(Triple("Coxa E.", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.calfCm?.let { add(Triple("Panturrilha", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
        measurement.hipCm?.let { add(Triple("Quadril", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
    }

    val bmiResult = BodyMetricsCalculator.calculateBmi(
        weightKg = measurement.weightKg,
        heightCm = measurement.heightCm
    )

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
        modifier = modifier.fillMaxWidth()
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(LimeTransparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = formattedDate,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "${items.size} ${if (items.size == 1) "medida registrada" else "medidas registradas"}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (onEditClick != null) {
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier.testTag("edit_measurement_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Editar medição",
                                tint = Lime400,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (onDeleteClick != null) {
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.testTag("delete_measurement_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Excluir medição",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // BMI card if available for this specific measurement
            if (bmiResult != null) {
                Spacer(modifier = Modifier.height(14.dp))
                BodyMetricCard(bmiResult = bmiResult)
            }

            Spacer(modifier = Modifier.height(16.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { (label, value, unit) ->
                    MeasurementBadge(label = label, value = value, unit = unit)
                }
            }

            // Actions bar (Edit button prominent)
            if (onEditClick != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onEditClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime400,
                        contentColor = com.example.ui.theme.BackgroundDark
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("edit_measurement_full_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Editar medição",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
