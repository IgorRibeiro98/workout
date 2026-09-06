package com.example.data.backup

import com.example.data.sync.dto.ExerciseRefDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Os contratos de serialização do backup (T16.4).
 *
 * O envelope e os três agregados que existem **só** no backup. Os outros seis reusam os DTOs da
 * T16.3 (`com.example.data.sync.dto`), montados pelo mesmo `SyncAggregateSnapshotBuilder`.
 *
 * Regras herdadas da T16.3 e que continuam valendo aqui:
 *
 * - **nenhum `localId` aparece.** Referência é sempre `syncId` ou `canonicalId`;
 * - **tempo é epoch millis UTC.** Sem fuso do aparelho no contrato;
 * - **nenhum caminho local aparece.** `content://`, `file://` e `/data/user/...` não são
 *   referências portáveis, e mídia continua local na T16.4.
 */

/**
 * Um agregado dentro do snapshot.
 *
 * [syncId] é a identidade **portátil** daquele agregado — `syncId` (UUID) para as raízes da T16.3,
 * e a chave derivada documentada no contrato para as outras. Nunca um `localId`.
 */
@Serializable
data class BackupItemDto(
    val entityType: String,
    val entitySchemaVersion: Int,
    val syncId: String,
    val payload: JsonElement
)

/**
 * De onde o snapshot veio. Diagnóstico, não autoridade.
 *
 * Serve para responder, meses depois, "qual versão do app e do banco produziu este backup" — que é
 * a primeira pergunta de qualquer investigação de restore.
 */
@Serializable
data class BackupSourceDto(
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val databaseVersion: Int? = null
)

/**
 * O snapshot completo enviado ao Spark Backend.
 *
 * ## `ownerUid` não está aqui, e isso é o contrato
 *
 * O dono do backup é derivado do Firebase ID Token verificado pelo servidor. Um `ownerUid` no
 * corpo seria ignorado — e não existir é melhor do que existir e ser ignorado, porque nenhuma
 * versão futura do servidor pode "aproveitar" um campo que nunca esteve lá.
 *
 * ## `capturedAt` é informativo
 *
 * Ele é o relógio **deste aparelho**. Quem decide qual backup é o mais recente é o servidor, pela
 * sequência dele: um celular com o relógio adiantado esconderia backups reais para sempre.
 */
@Serializable
data class BackupSnapshotDto(
    val clientBackupId: String,
    val backupSchemaVersion: Int,
    val deviceId: String,
    val capturedAt: Long,
    val source: BackupSourceDto,
    val items: List<BackupItemDto>
)

/**
 * Uma customização de exercício feita pelo usuário.
 *
 * A identidade é a **do exercício alvo**, não uma nova: dois aparelhos que customizam o mesmo
 * supino estão falando da mesma coisa, e dois UUIDs aleatórios criariam duas customizações
 * concorrentes para um exercício só (`data-classification-matrix.md`).
 *
 * `customPhotoUri` **não está aqui**, de propósito. Ele é um `content://` deste aparelho: não é
 * portátil, não resolve em outro dispositivo e serializá-lo criaria uma referência quebrada com
 * cara de referência válida. Mídia fica local na T16.4, e a UI diz isso ao usuário.
 */
@Serializable
data class ExerciseOverrideBackupDto(
    val exercise: ExerciseRefDto,
    val displayName: String? = null,
    val notes: String? = null,
    val defaultRestSeconds: Int? = null,
    val updatedAt: Long
)

/**
 * Uma meta semanal do histórico.
 *
 * A PK natural (`effectiveFromWeekStartEpochDay`) já é global: a mesma semana é a mesma semana em
 * qualquer aparelho. Não recebe `syncId` porque não precisa de um.
 */
@Serializable
data class WeeklyGoalBackupDto(
    val effectiveFromWeekStartEpochDay: Long,
    val goal: Int,
    val createdAt: Long
)

/**
 * As preferências que descrevem o **atleta**, não o aparelho.
 *
 * A matriz de dados separava as duas famílias e marcava estas como candidatas ao backup da T16.4.
 * Ficam de fora, e continuam locais: tema, vibração, som, tela ligada, notificação de timer,
 * estado do timer de descanso, versões de catálogo instaladas, `deviceId`, estado da nuvem e a
 * chave da ExerciseDB — que é credencial e não sai do aparelho.
 *
 * Nada foi movido de DataStore para Room para poder entrar no backup: o snapshot tem DTO próprio,
 * que é exatamente o que a matriz previa.
 */
@Serializable
data class UserPreferencesBackupDto(
    val weeklyGoal: Int,
    val useKg: Boolean,
    val defaultRestSeconds: Int,
    val defaultExerciseRestSeconds: Int,
    val rirRpeEnabled: Boolean,
    val autoRestTimerOnSet: Boolean
)

/** A metadata que o servidor devolve. Nunca o snapshot. */
@Serializable
data class BackupMetadataDto(
    val backupId: String,
    val clientBackupId: String,
    val backupSchemaVersion: Int,
    /** Relógio do **servidor**. É esta a autoridade de "quando" e de "qual é o mais recente". */
    val createdAt: Long,
    val itemCount: Int,
    val sizeBytes: Long,
    val payloadHash: String
)
