package com.example.data.mapper

import com.example.data.local.BodyMeasurementEntity
import com.example.domain.evolution.model.BodyMeasurement

fun BodyMeasurementEntity.toDomain(): BodyMeasurement {
    return BodyMeasurement(
        id = id,
        date = date,
        weightKg = weightKg,
        heightCm = heightCm,
        waistCm = waistCm,
        abdomenCm = abdomenCm,
        chestCm = chestCm,
        leftArmCm = leftArmCm,
        rightArmCm = rightArmCm,
        leftThighCm = leftThighCm,
        rightThighCm = rightThighCm,
        leftCalfCm = calfCm,
        rightCalfCm = calfCm,
        hipCm = hipCm,
        bodyFatPercentage = bodyFatPercentage
    )
}

fun List<BodyMeasurementEntity>.toDomain(): List<BodyMeasurement> {
    return map { it.toDomain() }
}
