package com.example.domain.body

import java.util.Locale
import kotlin.math.roundToInt

data class BmiResult(
    val bmi: Float,
    val formattedBmi: String,
    val classification: String,
    val isObese: Boolean = false,
    val isOverweight: Boolean = false,
    val isNormal: Boolean = false,
    val isUnderweight: Boolean = false
)

object BodyMetricsCalculator {

    /**
     * Calculates BMI (IMC) dynamically from weight in kilograms and height in centimeters.
     * Formula: weightKg / (heightInMeters ^ 2)
     * Returns null if either weightKg or heightCm is null or non-positive.
     */
    fun calculateBmi(weightKg: Float?, heightCm: Float?): BmiResult? {
        if (weightKg == null || heightCm == null) return null
        if (weightKg <= 0f || heightCm <= 0f) return null

        val heightM = heightCm / 100f
        val rawBmi = weightKg / (heightM * heightM)
        if (rawBmi.isInfinite() || rawBmi.isNaN()) return null

        // Round to 1 decimal place
        val roundedBmi = (rawBmi * 10f).roundToInt() / 10f
        val formattedBmi = String.format(Locale.US, "%.1f", roundedBmi)

        val classification = when {
            roundedBmi < 18.5f -> "Baixo peso"
            roundedBmi < 25.0f -> "Normal"
            roundedBmi < 30.0f -> "Sobrepeso"
            else -> "Obesidade"
        }

        return BmiResult(
            bmi = roundedBmi,
            formattedBmi = formattedBmi,
            classification = classification,
            isUnderweight = roundedBmi < 18.5f,
            isNormal = roundedBmi in 18.5f..24.99f,
            isOverweight = roundedBmi in 25.0f..29.99f,
            isObese = roundedBmi >= 30.0f
        )
    }
}
