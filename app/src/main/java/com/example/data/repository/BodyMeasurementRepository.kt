package com.example.data.repository

import com.example.data.local.BodyMeasurementDao
import com.example.data.local.BodyMeasurementEntity
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncMutationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BodyMeasurementRepository(
    val dao: BodyMeasurementDao,
    var onMeasurementChanged: (suspend () -> Unit)? = null,
    private val syncMutations: SyncMutationCoordinator = SyncMutationCoordinator.disabled()
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
        val id = syncMutations.mutate {
            val id = dao.insertMeasurement(measurement)
            upsert(SyncEntityType.BODY_MEASUREMENT, measurement.syncId)
            id
        }
        // Fora da transação de propósito: o gatilho recalcula gamificação e evolução, que são
        // dados derivados. Prender esse trabalho ao commit da medida acoplaria uma escrita
        // pessoal ao recálculo inteiro.
        try {
            onMeasurementChanged?.invoke()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        id
    }

    suspend fun updateMeasurement(measurement: BodyMeasurementEntity) = withContext(Dispatchers.IO) {
        syncMutations.mutate {
            // `syncId` é imutável. A tela de medidas remonta a entidade a partir do formulário ao
            // editar, e uma entidade recém-construída traz um `syncId` novo — gravá-lo trocaria a
            // identidade global de uma medida que já existe. A identidade guardada vence, e a UI
            // continua sem precisar saber que ela existe.
            val stored = dao.getMeasurementSyncId(measurement.id)
            val preserved = if (stored.isNullOrBlank()) measurement else measurement.copy(syncId = stored)
            dao.updateMeasurement(preserved)
            upsert(SyncEntityType.BODY_MEASUREMENT, preserved.syncId)
        }
        try {
            onMeasurementChanged?.invoke()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteMeasurement(measurement: BodyMeasurementEntity) = withContext(Dispatchers.IO) {
        syncMutations.mutate {
            dao.deleteMeasurement(measurement)
            delete(SyncEntityType.BODY_MEASUREMENT, measurement.syncId)
        }
    }

    suspend fun deleteMeasurementById(id: Long) = withContext(Dispatchers.IO) {
        syncMutations.mutate {
            // A identidade global é resolvida **antes** do delete: depois dele a linha não existe
            // mais e não haveria o que registrar.
            val syncId = dao.getMeasurementSyncId(id)
            dao.deleteMeasurementById(id)
            syncId?.let { delete(SyncEntityType.BODY_MEASUREMENT, it) }
        }
    }
}
