package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Recibo local de importação de treino compartilhado (T17.7).
 *
 * Garante idempotência local: se o usuário já importou este shareId, tentativas repetidas
 * ou retentativas após falha de rede/crash não duplicam o WorkoutTemplate no Room.
 */
@Entity(tableName = "workout_share_import_receipts")
data class WorkoutShareImportReceiptEntity(
    @PrimaryKey
    val shareId: String,
    val importedTemplateLocalId: Long,
    val createdAt: Long
)
