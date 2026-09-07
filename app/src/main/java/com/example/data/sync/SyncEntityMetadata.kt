package com.example.data.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * O que este aparelho sabe sobre a versão **remota** de um agregado (T16.6).
 *
 * ```text
 * WorkoutTemplateEntity   →  o treino do usuário          (domínio)
 * EntitySyncMetadata      →  "o servidor está na revision 4 deste treino"   (mecanismo)
 * ```
 *
 * ## Por que uma tabela separada, e não colunas nas entidades de domínio
 *
 * `lastKnownServerRevision`, `lastSyncedPayloadHash` e `lastSyncedAt` não descrevem o treino: eles
 * descrevem o estado de uma conversa com o servidor. Espalhá-los como colunas em
 * `workout_templates`, `body_measurements`, `check_ins` e `exercises` significaria migração em
 * cinco tabelas, cinco lugares para esquecer de atualizar, e um modelo de domínio carregando
 * mecanismo de transporte.
 *
 * Separado, ele também some sozinho quando precisa: um restore substitui o dataset e limpa esta
 * tabela junto, sem tocar em coluna de domínio nenhuma.
 *
 * ## Revision e hash respondem coisas diferentes
 *
 * `lastKnownServerRevision` responde **"que versão remota eu conheço?"** — é o `baseRevision` do
 * próximo push, e é o que detecta escrita stale. `lastSyncedPayloadHash` responde **"o conteúdo
 * remoto era este?"** — é o que permite reconhecer eco e convergência sem gastar revision. Um não
 * substitui o outro.
 */
@Entity(tableName = "sync_entity_metadata", primaryKeys = ["ownerUid", "entityType", "entitySyncId"])
data class EntitySyncMetadataEntity(

    /**
     * A conta dona do dataset.
     *
     * Faz parte da chave para que nada aqui atravesse uma troca de dono: revision de outra conta é
     * informação sobre um servidor que não é o desta conta.
     */
    val ownerUid: String,

    /** Nome de [SyncEntityType]. */
    val entityType: String,

    /** Identidade global do agregado (`syncId`). */
    val entitySyncId: String,

    /** A última `serverRevision` que este aparelho viu. Vira `baseRevision` no próximo push. */
    val lastKnownServerRevision: Long,

    /** O hash canônico do conteúdo daquela revision, quando conhecido. */
    val lastSyncedPayloadHash: String? = null,

    /** Quando esta linha foi atualizada, em epoch millis UTC. Metadado, nunca árbitro. */
    val lastSyncedAt: Long
)

@Dao
interface EntitySyncMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: EntitySyncMetadataEntity)

    @Query(
        """
        SELECT * FROM sync_entity_metadata
        WHERE ownerUid = :ownerUid AND entityType = :entityType AND entitySyncId = :entitySyncId
        LIMIT 1
        """
    )
    suspend fun get(
        ownerUid: String,
        entityType: String,
        entitySyncId: String
    ): EntitySyncMetadataEntity?

    @Query("SELECT * FROM sync_entity_metadata WHERE ownerUid = :ownerUid ORDER BY entityType, entitySyncId")
    suspend fun allFor(ownerUid: String): List<EntitySyncMetadataEntity>

    /** Usado pelo restore: o dataset foi substituído, e o que se sabia sobre ele não vale mais. */
    @Query("DELETE FROM sync_entity_metadata")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM sync_entity_metadata")
    suspend fun count(): Int
}
