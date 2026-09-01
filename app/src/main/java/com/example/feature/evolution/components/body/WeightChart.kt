package com.example.feature.evolution.components.body

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BodyMeasurementEntity
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Composable
fun WeightChart(
    measurements: List<BodyMeasurementEntity>,
    modifier: Modifier = Modifier
) {
    val weightData = remember(measurements) {
        measurements
            .filter { it.weightKg != null && it.weightKg > 0f }
            .sortedWith(compareBy({ it.date }, { it.createdAt }))
    }

    val dateFormatter = remember { SimpleDateFormat("dd/MM", Locale("pt", "BR")) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("weight_chart")
    ) {
        if (weightData.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(SurfaceHighlight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhum histórico de peso registrado",
                    color = TextTertiary,
                    fontSize = 13.sp
                )
            }
        } else if (weightData.size == 1) {
            val single = weightData.first()
            val weightFormatted = String.format(Locale.US, "%.1f", single.weightKg).replace('.', ',')
            val dateFormatted = dateFormatter.format(Date(single.date))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(SurfaceHighlight),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Lime400, CircleShape)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$weightFormatted kg",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Registrado em $dateFormatted",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            val weights = weightData.mapNotNull { it.weightKg }
            val minWeight = weights.minOrNull() ?: 0f
            val maxWeight = weights.maxOrNull() ?: 100f
            val range = max(0.5f, maxWeight - minWeight)

            val minDisplay = (minWeight - 0.5f)
            val maxDisplay = (maxWeight + 0.5f)
            val totalDisplayRange = max(1f, maxDisplay - minDisplay)

            val primaryColor = Lime400
            val gradientColor = LimeTransparent
            val gridColor = Color(0x1FFFFFFF)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                val width = size.width
                val height = size.height
                val bottomPadding = 20f
                val chartHeight = height - bottomPadding

                val stepX = if (weightData.size > 1) width / (weightData.size - 1) else width / 2

                // Draw 3 horizontal guide lines
                val numGridLines = 3
                for (i in 0 until numGridLines) {
                    val y = (chartHeight / (numGridLines - 1)) * i
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1f
                    )
                }

                // Compute points
                val points = weightData.mapIndexed { index, item ->
                    val weight = item.weightKg ?: minWeight
                    val normalizedY = (weight - minDisplay) / totalDisplayRange
                    val x = index * stepX
                    val y = chartHeight - (normalizedY * chartHeight)
                    Offset(x, y)
                }

                // Draw gradient filled area under the curve
                val fillPath = Path().apply {
                    if (points.isNotEmpty()) {
                        moveTo(points.first().x, chartHeight)
                        lineTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            val prev = points[i - 1]
                            val current = points[i]
                            val controlX = (prev.x + current.x) / 2f
                            cubicTo(controlX, prev.y, controlX, current.y, current.x, current.y)
                        }
                        lineTo(points.last().x, chartHeight)
                        close()
                    }
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.35f),
                            gradientColor.copy(alpha = 0.0f)
                        ),
                        startY = 0f,
                        endY = chartHeight
                    )
                )

                // Draw smooth line
                val linePath = Path().apply {
                    if (points.isNotEmpty()) {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            val prev = points[i - 1]
                            val current = points[i]
                            val controlX = (prev.x + current.x) / 2f
                            cubicTo(controlX, prev.y, controlX, current.y, current.x, current.y)
                        }
                    }
                }

                drawPath(
                    path = linePath,
                    color = primaryColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Draw point markers
                points.forEach { point ->
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.4f),
                        radius = 6.dp.toPx(),
                        center = point
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 3.5.dp.toPx(),
                        center = point
                    )
                }
            }

            // X-axis Date Labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val firstDate = dateFormatter.format(Date(weightData.first().date))
                val lastDate = dateFormatter.format(Date(weightData.last().date))

                Text(
                    text = firstDate,
                    color = TextTertiary,
                    fontSize = 11.sp
                )

                if (weightData.size > 2) {
                    val midIndex = weightData.size / 2
                    val midDate = dateFormatter.format(Date(weightData[midIndex].date))
                    Text(
                        text = midDate,
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }

                Text(
                    text = lastDate,
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
