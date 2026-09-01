package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.BMICategory
import com.example.domain.evolution.model.BMIResult
import kotlin.math.roundToInt

object BodyMetricsCalculator {

    /**
     * Calculates BMI (IMC) from weight in kilograms and height in centimeters.
     * Formula: weight / (heightInMeters ^ 2)
     *
     * Example:
     * Peso: 88.4kg, Altura: 171cm -> IMC: 30.2 (OBESITY)
     */
    fun calculateBMI(weightKg: Float?, heightCm: Float?): BMIResult? {
        if (weightKg == null || heightCm == null) return null
        if (weightKg <= 0f || heightCm <= 0f) return null

        val heightM = heightCm / 100f
        val rawBmi = weightKg / (heightM * heightM)
        if (rawBmi.isInfinite() || rawBmi.isNaN()) return null

        // Round to 1 decimal place
        val roundedBmi = (rawBmi * 10f).roundToInt() / 10f

        val category = when {
            roundedBmi < 18.5f -> BMICategory.UNDERWEIGHT
            roundedBmi < 25.0f -> BMICategory.NORMAL
            roundedBmi < 30.0f -> BMICategory.OVERWEIGHT
            else -> BMICategory.OBESITY
        }

        return BMIResult(
            value = roundedBmi,
            category = category
        )
    }

    fun calculateBMI(weightKg: Float, heightCm: Float): BMIResult? {
        return calculateBMI(weightKg as Float?, heightCm as Float?)
    }
}
