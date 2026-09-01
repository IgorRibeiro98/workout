package com.example.domain.evolution.model

data class BMIResult(
    val value: Float,
    val category: BMICategory
)

enum class BMICategory {
    UNDERWEIGHT,
    NORMAL,
    OVERWEIGHT,
    OBESITY
}
