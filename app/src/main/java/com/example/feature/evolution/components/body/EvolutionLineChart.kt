package com.example.feature.evolution.components.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class ChartPoint(
    val date: Long,
    val value: Float
)

@Composable
fun EvolutionLineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = Lime400,
    gradientColor: Color = LimeTransparent,
    unit: String = "",
    testTag: String = "evolution_line_chart"
) {
    if (points.size < 2) return

    val sortedPoints = remember(points) { points.sortedBy { it.date } }
    val dateFormatter = remember { SimpleDateFormat("dd/MM", Locale("pt", "BR")) }

    val values = sortedPoints.map { it.value }
    val minValue = values.minOrNull() ?: 0f
    val maxValue = values.maxOrNull() ?: 100f
    val paddingValue = if (maxValue == minValue) 1f else (maxValue - minValue) * 0.1f
    val minDisplay = minValue - paddingValue
    val maxDisplay = maxValue + paddingValue
    val totalRange = max(0.1f, maxDisplay - minDisplay)

    val gridColor = Color(0x1FFFFFFF)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            val width = size.width
            val height = size.height
            val bottomPadding = 12f
            val chartHeight = height - bottomPadding

            val stepX = width / (sortedPoints.size - 1)

            // Draw horizontal grid lines
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

            // Compute coordinate points
            val coords = sortedPoints.mapIndexed { index, item ->
                val normalizedY = (item.value - minDisplay) / totalRange
                val x = index * stepX
                val y = chartHeight - (normalizedY * chartHeight)
                Offset(x, y)
            }

            // Draw gradient area under the curve
            val fillPath = Path().apply {
                if (coords.isNotEmpty()) {
                    moveTo(coords.first().x, chartHeight)
                    lineTo(coords.first().x, coords.first().y)
                    for (i in 1 until coords.size) {
                        val prev = coords[i - 1]
                        val current = coords[i]
                        val controlX = (prev.x + current.x) / 2f
                        cubicTo(controlX, prev.y, controlX, current.y, current.x, current.y)
                    }
                    lineTo(coords.last().x, chartHeight)
                    close()
                }
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.35f),
                        gradientColor.copy(alpha = 0.0f)
                    ),
                    startY = 0f,
                    endY = chartHeight
                )
            )

            // Draw line
            val linePath = Path().apply {
                if (coords.isNotEmpty()) {
                    moveTo(coords.first().x, coords.first().y)
                    for (i in 1 until coords.size) {
                        val prev = coords[i - 1]
                        val current = coords[i]
                        val controlX = (prev.x + current.x) / 2f
                        cubicTo(controlX, prev.y, controlX, current.y, current.x, current.y)
                    }
                }
            }

            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Draw points
            coords.forEach { point ->
                drawCircle(
                    color = lineColor.copy(alpha = 0.3f),
                    radius = 6.dp.toPx(),
                    center = point
                )
                drawCircle(
                    color = lineColor,
                    radius = 3.5.dp.toPx(),
                    center = point
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // X-axis Date Labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val firstDate = dateFormatter.format(Date(sortedPoints.first().date))
            val lastDate = dateFormatter.format(Date(sortedPoints.last().date))

            Text(
                text = firstDate,
                color = TextTertiary,
                fontSize = 11.sp
            )

            if (sortedPoints.size > 2) {
                val midIndex = sortedPoints.size / 2
                val midDate = dateFormatter.format(Date(sortedPoints[midIndex].date))
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
