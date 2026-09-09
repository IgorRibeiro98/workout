package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WorkoutShareReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: WorkoutShareImportReceiptEntity)

    @Query("SELECT * FROM workout_share_import_receipts WHERE shareId = :shareId LIMIT 1")
    suspend fun findReceipt(shareId: String): WorkoutShareImportReceiptEntity?

    @Query("SELECT importedTemplateLocalId FROM workout_share_import_receipts WHERE shareId = :shareId LIMIT 1")
    suspend fun getImportedTemplateId(shareId: String): Long?
}
