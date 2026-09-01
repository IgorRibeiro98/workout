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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    onDeleteClick: (() -> Unit)? = null
) {
    val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val formattedDate = dateFormatter.format(Date(measurement.date))

    val items = buildList {
        measurement.weightKg?.let { add(Triple("Peso", String.format(Locale.getDefault(), "%.1f", it), "kg")) }
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
        measurement.heightCm?.let { add(Triple("Altura", String.format(Locale.getDefault(), "%.1f", it), "cm")) }
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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

                if (onDeleteClick != null) {
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.testTag("delete_measurement_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Excluir medição",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
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
        }
    }
}

@Composable
fun MeasurementBadge(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(com.example.ui.theme.BackgroundDark)
            .border(1.dp, BorderLight, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = Lime400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}
