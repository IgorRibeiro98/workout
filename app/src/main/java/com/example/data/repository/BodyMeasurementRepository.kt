package com.example.data.repository

import com.example.data.local.BodyMeasurementDao
import com.example.data.local.BodyMeasurementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BodyMeasurementRepository(
    val dao: BodyMeasurementDao
) {
    val allMeasurements: Flow<List<BodyMeasurementEntity>> = dao.getAllMeasurements()
    val latestMeasurement: Flow<BodyMeasurementEntity?> = dao.getLatestMeasurement()

    suspend fun getAllMeasurementsSync(): List<BodyMeasurementEntity> = withContext(Dispatchers.IO) {
        dao.getAllMeasurementsSync()
    }

    suspend fun getMeasurementById(id: Long): BodyMeasurementEntity? = withContext(Dispatchers.IO) {
        dao.getMeasurementByIdSync(id)
    }

    suspend fun insertMeasurement(measurement: BodyMeasurementEntity): Long = withContext(Dispatchers.IO) {
        dao.insertMeasurement(measurement)
    }

    suspend fun updateMeasurement(measurement: BodyMeasurementEntity) = withContext(Dispatchers.IO) {
        dao.updateMeasurement(measurement)
    }

    suspend fun deleteMeasurement(measurement: BodyMeasurementEntity) = withContext(Dispatchers.IO) {
        dao.deleteMeasurement(measurement)
    }

    suspend fun deleteMeasurementById(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteMeasurementById(id)
    }
}
