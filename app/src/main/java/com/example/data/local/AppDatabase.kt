package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ExerciseEntity::class,
        WorkoutProgramEntity::class,
        WorkoutTemplateEntity::class,
        WorkoutTemplateExerciseEntity::class,
        WorkoutSessionEntity::class,
        ExerciseSessionEntity::class,
        SetLogEntity::class,
        ExerciseAlternativeEntity::class,
        CheckInEntity::class,
        PersonalRecordEntity::class,
        ExerciseUserOverrideEntity::class,
        ExerciseEducationEntity::class,
        ExerciseMediaEntity::class,
        ExerciseProgressionEntity::class,
        ExerciseSafetyEntity::class,
        ExerciseSubstitutionPremiumEntity::class,
        ExerciseAiContextEntity::class,
        ExerciseBiomechanicsEntity::class,
        ExerciseExecutionEntity::class,
        BodyMeasurementEntity::class,
        GamificationEventEntity::class,
        XpTransactionEntity::class,
        WeeklyGoalHistoryEntity::class,
        AchievementUnlockEntity::class,
        com.example.data.sync.SyncOutboxEntryEntity::class,
        com.example.data.backup.CloudDataBindingEntity::class,
        com.example.data.backup.BackupAttemptEntity::class,
        com.example.data.restore.RestoreAttemptEntity::class
    ],
    version = 33,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun gamificationEventDao(): GamificationEventDao
    abstract fun xpTransactionDao(): XpTransactionDao
    abstract fun weeklyGoalDao(): WeeklyGoalDao
    abstract fun achievementDao(): AchievementDao
    abstract fun syncOutboxDao(): com.example.data.sync.SyncOutboxDao
    abstract fun cloudDataBindingDao(): com.example.data.backup.CloudDataBindingDao
    abstract fun backupAttemptDao(): com.example.data.backup.BackupAttemptDao
    abstract fun restoreAttemptDao(): com.example.data.restore.RestoreAttemptDao
    abstract fun restoreDao(): com.example.data.restore.RestoreDao

    companion object {

        /**
         * A versão do schema local, como número.
         *
         * Existe para que o backup possa registrar de qual banco o snapshot saiu sem duplicar o
         * literal da anotação. **Não** é `backupSchemaVersion`, que é a versão do formato de
         * backup e evolui por conta própria (`contracts/backup/v1/README.md`).
         */
        const val SCHEMA_VERSION: Int = 33

        /**
         * T16.3 — identidade global dos dados pessoais + Outbox transacional.
         *
         * A migração é **aditiva**: nenhuma tabela é recriada, nenhuma coluna existente muda de
         * significado, nenhuma chave estrangeira é substituída. `templateId`, `sessionId` e
         * `exerciseSessionId` continuam sendo `localId` — trocar relação local por UUID
         * reescreveria o schema inteiro sem ganho nenhum, e é justamente o que a T16.3 não faz.
         *
         * Para cada tabela que passa a ter identidade global, a ordem é sempre a mesma:
         *
         * ```text
         * ADD COLUMN syncId   →   backfill de todas as linhas   →   índice UNIQUE
         * ```
         *
         * O índice vem por último de propósito. Se o backfill produzisse duas linhas iguais — o
         * que nenhum UUID aleatório faz na prática, mas "UUID nunca colide" não é uma garantia do
         * banco —, a criação do índice falharia e a migração inteira seria revertida. É a
         * diferença entre falhar alto e corromper em silêncio.
         *
         * `ADD COLUMN NOT NULL` exige `DEFAULT` no SQLite; o `DEFAULT ''` existe só para permitir
         * o `ALTER TABLE` e some de vista assim que o backfill roda. O Room não valida
         * `defaultValue` de coluna que a entidade não declara com `@ColumnInfo(defaultValue = ...)`,
         * então o schema exportado continua batendo.
         */
        /**
         * T16.4 — adoção explícita do dataset por uma Conta Spark + tentativa de backup durável.
         *
         * Aditiva: duas tabelas novas, nenhuma existente é tocada. Nenhum treino, sessão, série ou
         * medida muda — o backup **lê** o domínio e não o reescreve.
         *
         * `cloud_data_binding` substitui as preferências `cloud_sync_state`/`cloud_sync_owner_uid`
         * da T16.3, que nunca chegaram a ser gravadas: a adoção não existia, então não há estado a
         * migrar. A informação passa para o Room porque ela é sobre **este banco**, e porque é o
         * que permite vincular, capturar o snapshot, ler o corte da Outbox e registrar a tentativa
         * na mesma transação.
         */
        /**
         * T16.5 — restore seguro: a tentativa de restauração precisa sobreviver ao processo.
         *
         * Uma tabela, e **nenhuma** alteração nas tabelas de domínio. O restore não acrescenta
         * coluna a treino, sessão ou medida: ele substitui o conteúdo delas dentro de uma
         * transação, e o que precisa ser durável é o *progresso* da operação — em que fase ela
         * está e onde estão os arquivos dela.
         *
         * `restore_attempts` guarda caminho de arquivo, não o snapshot. O documento baixado e o
         * snapshot de segurança vivem no armazenamento privado do app: eles são grandes, são
         * rebaixáveis (o download é read-only no servidor) e não têm por que atravessar a
         * transação que substitui o dataset.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `restore_attempts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `restoreAttemptId` TEXT NOT NULL,
                        `backupId` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `payloadHash` TEXT NOT NULL,
                        `backupSchemaVersion` INTEGER NOT NULL,
                        `backupCreatedAt` INTEGER NOT NULL,
                        `datasetWasUnbound` INTEGER NOT NULL,
                        `downloadPath` TEXT,
                        `safetySnapshotPath` TEXT,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `failureReason` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_restore_attempts_restoreAttemptId` " +
                        "ON `restore_attempts` (`restoreAttemptId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_restore_attempts_ownerUid_status_id` " +
                        "ON `restore_attempts` (`ownerUid`, `status`, `id`)"
                )
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Uma linha, sempre `id = 1`: um banco tem um dono, não uma lista deles.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_data_binding` (
                        `id` INTEGER PRIMARY KEY NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `boundAt` INTEGER NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `lastSuccessfulBackupId` TEXT,
                        `lastSuccessfulBackupAt` INTEGER
                    )
                    """.trimIndent()
                )

                // A tentativa guarda o payload inteiro: é ela que sobrevive a process death e
                // permite reenviar **os mesmos bytes** com o mesmo `clientBackupId`.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `backup_attempts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `clientBackupId` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `backupSchemaVersion` INTEGER NOT NULL,
                        `coveredOutboxSequence` INTEGER NOT NULL,
                        `payloadHash` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `itemCount` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastAttemptAt` INTEGER,
                        `failureReason` TEXT,
                        `serverBackupId` TEXT,
                        `serverCreatedAt` INTEGER,
                        `serverPayloadHash` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_attempts_clientBackupId` " +
                        "ON `backup_attempts` (`clientBackupId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_backup_attempts_ownerUid_status_id` " +
                        "ON `backup_attempts` (`ownerUid`, `status`, `id`)"
                )
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- Identidade global das raízes de agregado pessoais -----------------------
                //
                // Só as raízes. Exercícios de treino, exercícios de sessão e séries executadas
                // não recebem identidade própria: eles nunca são referenciados de fora, não têm
                // ciclo de vida independente e viajam dentro do snapshot da raiz.
                listOf(
                    "workout_programs",
                    "workout_templates",
                    "workout_sessions",
                    "body_measurements",
                    "check_ins"
                ).forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE `$table` SET `syncId` = ${com.example.data.sync.SyncIds.SQLITE_RANDOM_UUID}")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_syncId` ON `$table` (`syncId`)")
                }

                // ---- Exercícios: identidade global só para os criados pelo usuário ------------
                //
                // A coluna é anulável e o catálogo canônico permanece nulo. `canonicalId` já é a
                // identidade oficial de um exercício de catálogo e continua sendo — a T16.3 não
                // cria uma identidade paralela para conteúdo que já tem uma.
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `syncId` TEXT")
                db.execSQL(
                    "UPDATE `exercises` SET `syncId` = ${com.example.data.sync.SyncIds.SQLITE_RANDOM_UUID} " +
                        "WHERE `isUserCreated` = 1"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_syncId` ON `exercises` (`syncId`)")

                // ---- Outbox transacional -----------------------------------------------------
                //
                // Nasce vazia e continua vazia enquanto a nuvem estiver desligada, que é o padrão
                // ao final da T16.3.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `clientMutationId` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entitySyncId` TEXT NOT NULL,
                        `operation` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastAttemptAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_clientMutationId` " +
                        "ON `sync_outbox` (`clientMutationId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_outbox_ownerUid_status_id` " +
                        "ON `sync_outbox` (`ownerUid`, `status`, `id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_outbox_entityType_entitySyncId` " +
                        "ON `sync_outbox` (`entityType`, `entitySyncId`)"
                )
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `weekly_goal_history` (
                        `effectiveFromWeekStartEpochDay` INTEGER NOT NULL,
                        `goal` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`effectiveFromWeekStartEpochDay`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `achievement_unlocks` (
                        `achievementId` TEXT NOT NULL,
                        `unlockedAt` INTEGER NOT NULL,
                        `triggerEventId` TEXT,
                        `definitionVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`achievementId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `xp_transactions` (
                        `id` TEXT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_xp_transactions_eventId` ON `xp_transactions` (`eventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_xp_transactions_createdAt` ON `xp_transactions` (`createdAt`)")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `gamification_events` (
                        `id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `dedupeKey` TEXT NOT NULL,
                        `metadataJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_gamification_events_dedupeKey` ON `gamification_events` (`dedupeKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gamification_events_type` ON `gamification_events` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gamification_events_timestamp` ON `gamification_events` (`timestamp`)")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE set_logs ADD COLUMN durationSeconds INTEGER")
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `exercise_sync_checkpoints`")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN normalizedName TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN muscleGroups TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN primaryMuscles TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN instructions TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN executionTips TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN commonMistakes TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN alternatives TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN youtubeUrl TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN externalReferences TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN origin TEXT DEFAULT 'SYSTEM'")
                db.execSQL("ALTER TABLE exercises ADD COLUMN isCurated INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN plannedOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN executionOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `body_measurements` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `weightKg` REAL,
                        `heightCm` REAL,
                        `bodyFatPercentage` REAL,
                        `waistCm` REAL,
                        `abdomenCm` REAL,
                        `chestCm` REAL,
                        `leftArmCm` REAL,
                        `rightArmCm` REAL,
                        `leftThighCm` REAL,
                        `rightThighCm` REAL,
                        `calfCm` REAL,
                        `hipCm` REAL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_body_measurements_date` ON `body_measurements` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_body_measurements_createdAt` ON `body_measurements` (`createdAt`)")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `exercise_sync_checkpoints`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `exercise_sync_checkpoints` (
                        `exerciseId` INTEGER NOT NULL,
                        `exerciseName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`exerciseId`)
                    )
                """.trimIndent())
            }
        }
        
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN bodyRegion TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN trainingGoals TEXT")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN repRangeMin INTEGER")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN repRangeMax INTEGER")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN incrementUpper REAL")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN incrementLower REAL")
                db.execSQL("ALTER TABLE exercise_media ADD COLUMN gifSource TEXT")
                db.execSQL("ALTER TABLE exercise_media ADD COLUMN videos TEXT")
            }
        }

val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN shortDescription TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN difficulty TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN exerciseType TEXT")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_education` (`exerciseId` INTEGER NOT NULL, `tips` TEXT, `commonMistakes` TEXT, `coachNotes` TEXT, PRIMARY KEY(`exerciseId`), FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_education_exerciseId` ON `exercise_education` (`exerciseId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_media` (`exerciseId` INTEGER NOT NULL, `exerciseDbId` TEXT, `youtubeVideoIds` TEXT, `gifUrl` TEXT, `imageUrls` TEXT, PRIMARY KEY(`exerciseId`), FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_media_exerciseId` ON `exercise_media` (`exerciseId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_progression` (`exerciseId` INTEGER NOT NULL, `repRange` TEXT, `standardSets` INTEGER, `progressionMethod` TEXT, `increaseRule` TEXT, PRIMARY KEY(`exerciseId`), FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_progression_exerciseId` ON `exercise_progression` (`exerciseId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_safety` (`exerciseId` INTEGER NOT NULL, `riskLevel` TEXT, `attentionPoints` TEXT, `commonDiscomforts` TEXT, PRIMARY KEY(`exerciseId`), FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_safety_exerciseId` ON `exercise_safety` (`exerciseId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_substitutions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exerciseId` INTEGER NOT NULL, `sameMovement` TEXT, `sameMuscle` TEXT, `notRecommended` TEXT, FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_substitutions_exerciseId` ON `exercise_substitutions` (`exerciseId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_ai_context` (`exerciseId` INTEGER NOT NULL, `objectives` TEXT, `keywords` TEXT, `decisionRules` TEXT, PRIMARY KEY(`exerciseId`), FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_ai_context_exerciseId` ON `exercise_ai_context` (`exerciseId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_biomechanics` (`exerciseId` INTEGER NOT NULL, `jointActions` TEXT, `rangeOfMotion` TEXT, `stabilityDemand` TEXT, `targetFeeling` TEXT, PRIMARY KEY(`exerciseId`), FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_biomechanics_exerciseId` ON `exercise_biomechanics` (`exerciseId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_execution` (`exerciseId` INTEGER NOT NULL, `setup` TEXT, `steps` TEXT, `breathing` TEXT, PRIMARY KEY(`exerciseId`), FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_execution_exerciseId` ON `exercise_execution` (`exerciseId`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN exerciseDbAliases TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_programs ADD COLUMN externalId TEXT")
                db.execSQL("ALTER TABLE workout_programs ADD COLUMN contentVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workout_programs_externalId ON workout_programs(externalId)")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    // Update legacy exercises to ensure they are marked as user created
                    // This prevents data destruction if they collide with new canonical IDs
                    db.execSQL("UPDATE exercises SET isUserCreated = 1, slug = 'legacy_' || id WHERE canonicalId IS NULL OR TRIM(canonicalId) = ''")
            }
        }
        
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Base schema creation if upgrading from v1
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN canonicalId TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN slug TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN contentVersion INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN aliases TEXT")
                    db.execSQL("ALTER TABLE workout_template_exercises ADD COLUMN plannedWeight REAL")
                    db.execSQL("ALTER TABLE workout_template_exercises ADD COLUMN machineLabel TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN nameEn TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN secondaryMuscles TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN movementPattern TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN substitutionGroup TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN exerciseDbSearch TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN externalExerciseId TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN gifUrl TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN lastVerifiedAt INTEGER")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS exercise_alternatives (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        exerciseId INTEGER NOT NULL,
                        alternativeExerciseId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE,
                        FOREIGN KEY(alternativeExerciseId) REFERENCES exercises(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_alternatives_exerciseId ON exercise_alternatives(exerciseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_alternatives_alternativeExerciseId ON exercise_alternatives(alternativeExerciseId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS personal_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        exerciseId INTEGER NOT NULL,
                        date INTEGER NOT NULL,
                        prType TEXT NOT NULL,
                        value REAL NOT NULL,
                        FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_records_exerciseId ON personal_records(exerciseId)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS exercise_alternatives (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        exerciseId INTEGER NOT NULL,
                        alternativeExerciseId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE,
                        FOREIGN KEY(alternativeExerciseId) REFERENCES exercises(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_alternatives_exerciseId ON exercise_alternatives(exerciseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_alternatives_alternativeExerciseId ON exercise_alternatives(alternativeExerciseId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS personal_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        exerciseId INTEGER NOT NULL,
                        date INTEGER NOT NULL,
                        prType TEXT NOT NULL,
                        value REAL NOT NULL,
                        FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_records_exerciseId ON personal_records(exerciseId)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE workout_templates ADD COLUMN dayOfWeek TEXT")
                    db.execSQL("ALTER TABLE workout_template_exercises ADD COLUMN notes TEXT")
                    db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN machineLabelSnapshot TEXT")
                    db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN primaryMuscleSnapshot TEXT")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_logs ADD COLUMN rpe REAL")
                    db.execSQL("ALTER TABLE set_logs ADD COLUMN rir INTEGER")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN isUserCreated INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN customPhotoUri TEXT")
                    db.execSQL("DELETE FROM exercise_alternatives WHERE id NOT IN (SELECT MIN(id) FROM exercise_alternatives GROUP BY exerciseId, alternativeExerciseId, type)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_exercise_alternatives_exerciseId_alternativeExerciseId_type ON exercise_alternatives(exerciseId, alternativeExerciseId, type)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS exercise_user_overrides (
                            exerciseId INTEGER PRIMARY KEY NOT NULL,
                            displayName TEXT,
                            notes TEXT,
                            customPhotoUri TEXT,
                            defaultRestSeconds INTEGER,
                            updatedAt INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_user_overrides_exerciseId ON exercise_user_overrides(exerciseId)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN restDurationSecondsSnapshot INTEGER")
                    db.execSQL("UPDATE exercises SET isUserCreated = 0 WHERE canonicalId IS NOT NULL AND TRIM(canonicalId) != ''")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN mappingStatus TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "workout_database"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33
                )
                .addCallback(DatabaseCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    populateDatabase(database.workoutDao())
                }
            }
        }

        suspend fun populateDatabase(dao: WorkoutDao) {
            val existing = dao.getAllProgramsSync()
            if (existing.isEmpty()) {
                val programId = dao.insertProgram(WorkoutProgramEntity(name = "ABCDE Hipertrofia", isCurrent = true))
                dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Quadríceps", shortIdentifier = "A", orderInProgram = 0))
                dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Peito + Costas", shortIdentifier = "B", orderInProgram = 1))
                dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Posterior", shortIdentifier = "C", orderInProgram = 2))
                dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Ombros + Braços", shortIdentifier = "D", orderInProgram = 3))
                dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Full Body", shortIdentifier = "E", orderInProgram = 4))
            }
        }
    }
}
