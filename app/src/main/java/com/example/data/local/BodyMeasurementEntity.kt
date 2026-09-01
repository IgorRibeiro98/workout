package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "body_measurements",
    indices = [
        Index(value = ["date"]),
        Index(value = ["createdAt"])
    ]
)
data class BodyMeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val date: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val weightKg: Float? = null,
    val heightCm: Float? = null,
    val bodyFatPercentage: Float? = null,
    val waistCm: Float? = null,
    val abdomenCm: Float? = null,
    val chestCm: Float? = null,
    val leftArmCm: Float? = null,
    val rightArmCm: Float? = null,
    val leftThighCm: Float? = null,
    val rightThighCm: Float? = null,
    val calfCm: Float? = null,
    val hipCm: Float? = null
)
