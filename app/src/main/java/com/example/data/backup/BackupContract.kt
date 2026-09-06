package com.example.data.backup

import com.example.data.sync.SyncEntityType

/**
 * O contrato de backup entre o Spark Android e o Spark Backend (T16.4).
 *
 * A definição legível — envelope, identidades, hash, idempotência, erros e tetos — vive em
 * `contracts/backup/v1/README.md`, junto das fixtures que os testes dos **dois** lados consomem.
 * Este arquivo é a metade Kotlin dela; a metade TypeScript é
 * `backend/src/modules/backup/backup.contract.ts`.
 *
 * ## Backup não é sincronização
 *
 * ```text
 * T16.4   Android ──snapshot completo──▶ Spark Backend      (existe)
 *         Android ◀───────────────────── Spark Backend      (não existe)
 * ```
 *
 * Um backup é uma cópia completa e **autocontida** do estado pessoal atual. Ele não depende de
 * backup anterior, de delta, da Outbox nem de servidor antigo. Restore é T16.5; push/pull
 * incremental é T16.6.
 */
object BackupContract {

    /**
     * Versão do **formato de backup**.
     *
     * Não se confunde com nenhuma das outras versões do projeto: não é `AppDatabase.version`, não
     * é o `/v1` da API, não é [BackupEntityType.schemaVersion] (que é por agregado) e não é a
     * versão do app.
     */
    const val SCHEMA_VERSION: Int = 1

    /** O caminho do endpoint de criação. Relativo à base do `SparkBackendClient`. */
    const val BACKUPS_PATH: String = "v1/backups"

    /** O caminho da metadata do backup mais recente. */
    const val LATEST_BACKUP_PATH: String = "v1/backups/latest"

    /** Identidade singleton das preferências sincronizáveis. */
    const val PREFERENCES_IDENTITY: String = "preferences"

    /** Prefixo da identidade natural de uma meta semanal. */
    const val WEEK_IDENTITY_PREFIX: String = "week:"

    /** Prefixo da identidade derivada de uma customização sobre exercício de catálogo. */
    const val CANONICAL_IDENTITY_PREFIX: String = "canonical:"

    /** Prefixo da identidade derivada de uma customização sobre exercício criado pelo usuário. */
    const val CUSTOM_IDENTITY_PREFIX: String = "custom:"
}

/**
 * Os agregados que um backup pode conter. Registry **fechado** dos dois lados.
 *
 * Os seis primeiros são exatamente os [SyncEntityType] da T16.3, e são montados pelo **mesmo**
 * `SyncAggregateSnapshotBuilder` — não existe um segundo serializador de treino no Spark.
 *
 * Os três últimos existem só no backup. Eles são dado pessoal do Grupo A da matriz, mas ainda não
 * produzem mutação incremental: a matriz da T16.3 já os marcava como "T16.4", e o snapshot
 * completo os cobre. Quando a T16.6 trouxer push incremental, eles precisarão de mutação própria —
 * está registrado como pendência em `ARCHITECTURE.md`.
 */
enum class BackupEntityType(
    /** A versão do payload **daquele agregado**, independente do envelope. */
    val schemaVersion: Int,
    /** O agregado equivalente da Outbox, quando existe. */
    val syncEntityType: SyncEntityType?
) {
    WORKOUT_PROGRAM(1, SyncEntityType.WORKOUT_PROGRAM),
    WORKOUT_TEMPLATE(1, SyncEntityType.WORKOUT_TEMPLATE),
    WORKOUT_SESSION(1, SyncEntityType.WORKOUT_SESSION),
    CUSTOM_EXERCISE(1, SyncEntityType.CUSTOM_EXERCISE),
    BODY_MEASUREMENT(1, SyncEntityType.BODY_MEASUREMENT),
    CHECK_IN(1, SyncEntityType.CHECK_IN),

    /** Identidade derivada do exercício alvo — ver `identity-contract.md`. */
    EXERCISE_OVERRIDE(1, null),

    /** Identidade natural: a semana de vigência. */
    WEEKLY_GOAL(1, null),

    /** Singleton por conta. Vem do DataStore, com DTO próprio. */
    USER_PREFERENCES(1, null);

    companion object {

        /** Os agregados que também têm identidade de Outbox, na ordem do registry. */
        val syncAggregates: List<BackupEntityType> = entries.filter { it.syncEntityType != null }
    }
}
