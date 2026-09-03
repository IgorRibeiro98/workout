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
        XpTransactionEntity::class
    ],
    version = 28,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun gamificationEventDao(): GamificationEventDao
    abstract fun xpTransactionDao(): XpTransactionDao
    
    companion object {

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
                    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28
                )
                .addCallback(DatabaseCallback())
                .fallbackToDestructiveMigration()
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
