package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    @Update
    suspend fun updateMeasurement(measurement: BodyMeasurementEntity)

    @Delete
    suspend fun deleteMeasurement(measurement: BodyMeasurementEntity)

    @Query("DELETE FROM body_measurements WHERE id = :id")
    suspend fun deleteMeasurementById(id: Long)

    /** Identidade global (T16.3): a medida por `syncId`, e o `syncId` de uma linha local. */
    @Query("SELECT * FROM body_measurements WHERE syncId = :syncId LIMIT 1")
    suspend fun getMeasurementBySyncId(syncId: String): BodyMeasurementEntity?

    /**
     * Apaga a medida pela identidade global (T16.7).
     *
     * Usado pelo apply remoto quando outro aparelho excluiu a medida. Local, direto, sem carregar
     * a entidade — e sem efeito colateral: gamificação e evolução são derivadas e reconciliadas.
     */
    @Query("DELETE FROM body_measurements WHERE syncId = :syncId")
    suspend fun deleteMeasurementBySyncId(syncId: String): Int

    @Query("SELECT syncId FROM body_measurements WHERE id = :id LIMIT 1")
    suspend fun getMeasurementSyncId(id: Long): String?

    /** Todas as medidas pela identidade global — a enumeração do snapshot de backup (T16.4). */
    @Query("SELECT syncId FROM body_measurements ORDER BY id ASC")
    suspend fun getAllMeasurementSyncIds(): List<String>
}
