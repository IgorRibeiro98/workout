package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyMeasurementDao {

    @Query("SELECT * FROM body_measurements ORDER BY date DESC, createdAt DESC")
    fun getAllMeasurements(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurements ORDER BY date DESC, createdAt DESC")
    suspend fun getAllMeasurementsSync(): List<BodyMeasurementEntity>

    @Query("SELECT * FROM body_measurements ORDER BY date DESC, createdAt DESC LIMIT 1")
    fun getLatestMeasurement(): Flow<BodyMeasurementEntity?>

    @Query("SELECT * FROM body_measurements ORDER BY date DESC, createdAt DESC LIMIT 1")
    suspend fun getLatestMeasurementSync(): BodyMeasurementEntity?

    @Query("SELECT * FROM body_measurements WHERE id = :id LIMIT 1")
    fun getMeasurementById(id: Long): Flow<BodyMeasurementEntity?>

    @Query("SELECT * FROM body_measurements WHERE id = :id LIMIT 1")
    suspend fun getMeasurementByIdSync(id: Long): BodyMeasurementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurement(measurement: BodyMeasurementEntity): Long

    @Delete
    suspend fun deleteMeasurement(measurement: BodyMeasurementEntity)

    @Query("DELETE FROM body_measurements WHERE id = :id")
    suspend fun deleteMeasurementById(id: Long)
}
