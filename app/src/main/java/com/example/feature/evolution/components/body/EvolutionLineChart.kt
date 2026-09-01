package com.example.feature.evolution.components.body

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class ChartPoint(
    val date: Long,
    val value: Float,
    val formattedDate: String = "",
    val label: String = "",
    val repetitions: Int? = null,
    val tooltipText: String = ""
)

@Composable
fun EvolutionLineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = Lime400,
    gradientColor: Color = LimeTransparent,
    unit: String = "",
    emptyStateMessage: String? = null,
    testTag: String = "evolution_line_chart"
) {
    if (points.isEmpty()) {
        if (emptyStateMessage != null) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E2124))
                    .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
                    .padding(20.dp)
                    .testTag("${testTag}_empty"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = emptyStateMessage,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    val sortedPoints = remember(points) { points.sortedBy { it.date } }
    val dateFormatter = remember { SimpleDateFormat("dd/MM", Locale("pt", "BR")) }
    val fullDateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")) }

    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    val selectedPoint = selectedIndex?.let { sortedPoints.getOrNull(it) }

    val values = sortedPoints.map { it.value }
    val minValue = values.minOrNull() ?: 0f
    val maxValue = values.maxOrNull() ?: 100f
    val paddingValue = if (maxValue == minValue) max(1f, maxValue * 0.1f) else (maxValue - minValue) * 0.15f
    val minDisplay = minValue - paddingValue
    val maxDisplay = maxValue + paddingValue
    val totalRange = max(0.1f, maxDisplay - minDisplay)

    val gridColor = Color(0x1FFFFFFF)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        // Tooltip Banner when a point is tapped
        AnimatedVisibility(
            visible = selectedPoint != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            selectedPoint?.let { point ->
                val textLines = if (point.tooltipText.isNotBlank()) {
                    point.tooltipText.split("\n")
                } else {
                    val formattedDate = if (point.formattedDate.isNotBlank()) point.formattedDate else fullDateFormatter.format(Date(point.date))
                    val valueLabel = if (point.label.isNotBlank()) point.label else "${point.value.toInt()} $unit".trim()
                    listOf(formattedDate, valueLabel)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(1.dp, lineColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("${testTag}_tooltip")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = textLines.firstOrNull() ?: "",
                                color = TextTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            textLines.drop(1).forEach { line ->
                                Text(
                                    text = line,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(lineColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Toque para alterar",
                                color = lineColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .pointerInput(sortedPoints) {
                    detectTapGestures { tapOffset ->
                        val width = size.width
                        if (sortedPoints.size == 1) {
                            selectedIndex = if (selectedIndex == 0) null else 0
                        } else {
                            val stepX = width / (sortedPoints.size - 1)
                            var closestIndex = 0
                            var minDistance = Float.MAX_VALUE
                            sortedPoints.indices.forEach { index ->
                                val x = index * stepX
                                val distance = abs(tapOffset.x - x)
                                if (distance < minDistance) {
                                    minDistance = distance
                                    closestIndex = index
                                }
                            }
                            selectedIndex = if (selectedIndex == closestIndex) null else closestIndex
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val bottomPadding = 12f
            val chartHeight = height - bottomPadding

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

            if (sortedPoints.size == 1) {
                val point = sortedPoints.first()
                val normalizedY = (point.value - minDisplay) / totalRange
                val y = chartHeight - (normalizedY * chartHeight)
                val centerOffset = Offset(width / 2f, y)

                drawLine(
                    color = lineColor.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 2.dp.toPx()
                )

                drawCircle(
                    color = lineColor.copy(alpha = 0.3f),
                    radius = 10.dp.toPx(),
                    center = centerOffset
                )
                drawCircle(
                    color = lineColor,
                    radius = 5.dp.toPx(),
                    center = centerOffset
                )
            } else {
                val stepX = width / (sortedPoints.size - 1)

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

                // Draw points and highlight selected point
                coords.forEachIndexed { index, point ->
                    val isSelected = index == selectedIndex
                    val baseRadius = if (isSelected) 8.dp.toPx() else 6.dp.toPx()
                    val innerRadius = if (isSelected) 5.dp.toPx() else 3.5.dp.toPx()

                    drawCircle(
                        color = if (isSelected) lineColor else lineColor.copy(alpha = 0.3f),
                        radius = baseRadius,
                        center = point
                    )
                    drawCircle(
                        color = if (isSelected) Color.White else lineColor,
                        radius = innerRadius,
                        center = point
                    )
                }
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
            } else if (sortedPoints.size == 1) {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (sortedPoints.size > 1) {
                Text(
                    text = lastDate,
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
