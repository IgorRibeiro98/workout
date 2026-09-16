package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Recibo local de importação de uma oferta compartilhada (T17.7 / T19.3).
 *
 * Garante idempotência local: se o usuário já importou este shareId, tentativas repetidas
 * ou retentativas após falha de rede/crash não duplicam o que foi criado no Room.
 *
 * Um recibo aponta para **uma** raiz importada — o treino (T17.7) ou o programa (T19.3) — e
 * exatamente uma das duas colunas vem preenchida. É o `localId` da cópia, não o do remetente:
 * ele serve só para conferir se a cópia ainda existe antes de dizer "já importado".
 */
@Entity(tableName = "workout_share_import_receipts")
data class WorkoutShareImportReceiptEntity(
    @PrimaryKey
    val shareId: String,
    val importedTemplateLocalId: Long? = null,
    val importedProgramLocalId: Long? = null,
    val createdAt: Long
)
