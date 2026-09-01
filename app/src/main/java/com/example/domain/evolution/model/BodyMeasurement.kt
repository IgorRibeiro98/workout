package com.example.domain.evolution.model

data class BodyMeasurement(
    val id: Long,
    val date: Long,
    val weightKg: Float?,
    val heightCm: Float?,
    val waistCm: Float?,
    val abdomenCm: Float?,
    val chestCm: Float?,
    val leftArmCm: Float?,
    val rightArmCm: Float?,
    val leftThighCm: Float?,
    val rightThighCm: Float?,
    val leftCalfCm: Float?,
    val rightCalfCm: Float?,
    val hipCm: Float?,
    val bodyFatPercentage: Float?
)
