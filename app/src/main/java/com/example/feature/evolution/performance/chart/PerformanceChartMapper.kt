package com.example.feature.evolution.performance.chart

import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.feature.evolution.components.body.ChartPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PerformanceChartMapper {

    private val fullDateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    private val shortDateFormatter = SimpleDateFormat("dd/MM", Locale("pt", "BR"))

    fun mapVolumePointToChartPoint(volumePoint: VolumePoint): ChartPoint {
        val fullDate = fullDateFormatter.format(Date(volumePoint.date))
        val shortDate = shortDateFormatter.format(Date(volumePoint.date))
        val volumeFormatted = String.format(Locale("pt", "BR"), "%,.0f kg", volumePoint.volume)
        val shortLabel = if (volumePoint.volume >= 1000f) {
            String.format(Locale("pt", "BR"), "%.1fk kg", volumePoint.volume / 1000f)
        } else {
            "${volumePoint.volume.toInt()} kg"
        }

        return ChartPoint(
            date = volumePoint.date,
            value = volumePoint.volume,
            formattedDate = shortDate,
            label = shortLabel,
            tooltipText = "$fullDate\nVolume: $volumeFormatted"
        )
    }

    fun mapVolumeHistoryToChartPoints(volumeHistory: List<VolumePoint>): List<ChartPoint> {
        return volumeHistory.map { mapVolumePointToChartPoint(it) }
    }

    fun mapStrengthPointToChartPoint(
        strengthPoint: StrengthPoint,
        exerciseName: String?
    ): ChartPoint {
        val fullDate = fullDateFormatter.format(Date(strengthPoint.date))
        val shortDate = shortDateFormatter.format(Date(strengthPoint.date))
        val name = exerciseName ?: "Exercício"
        val weightStr = if (strengthPoint.weight % 1f == 0f) "${strengthPoint.weight.toInt()}kg" else "${strengthPoint.weight}kg"
        val reps = strengthPoint.repetitions ?: 0
        val repsStr = if (reps > 0) " x $reps" else ""

        return ChartPoint(
            date = strengthPoint.date,
            value = strengthPoint.weight,
            formattedDate = shortDate,
            label = "$weightStr$repsStr",
            repetitions = strengthPoint.repetitions,
            tooltipText = "$fullDate\n$name\n$weightStr$repsStr"
        )
    }

    fun mapStrengthHistoryToChartPoints(
        strengthHistory: List<StrengthPoint>,
        exerciseName: String?
    ): List<ChartPoint> {
        return strengthHistory.map { mapStrengthPointToChartPoint(it, exerciseName) }
    }
}
