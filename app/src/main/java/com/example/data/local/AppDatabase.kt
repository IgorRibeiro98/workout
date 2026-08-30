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
        ExerciseUserOverrideEntity::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
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
                try {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN canonicalId TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN slug TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN contentVersion INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN aliases TEXT")
                    db.execSQL("ALTER TABLE workout_template_exercises ADD COLUMN plannedWeight REAL")
                    db.execSQL("ALTER TABLE workout_template_exercises ADD COLUMN machineLabel TEXT")
                } catch (e: Exception) {
                    // Ignore column already exists
                }
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN nameEn TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN secondaryMuscles TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN movementPattern TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN substitutionGroup TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN exerciseDbSearch TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN externalExerciseId TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN gifUrl TEXT")
                    db.execSQL("ALTER TABLE exercises ADD COLUMN lastVerifiedAt INTEGER")
                } catch (e: Exception) {
                    // Ignore column already exists
                }
                
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
                try {
                    db.execSQL("ALTER TABLE workout_templates ADD COLUMN dayOfWeek TEXT")
                    db.execSQL("ALTER TABLE workout_template_exercises ADD COLUMN notes TEXT")
                    db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN machineLabelSnapshot TEXT")
                    db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN primaryMuscleSnapshot TEXT")
                } catch (e: Exception) {
                    // Ignore if columns exist
                }
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE set_logs ADD COLUMN rpe REAL")
                    db.execSQL("ALTER TABLE set_logs ADD COLUMN rir INTEGER")
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN isUserCreated INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) { }
                try {
                    db.execSQL("ALTER TABLE exercises ADD COLUMN customPhotoUri TEXT")
                } catch (e: Exception) { }
                try {
                    db.execSQL("DELETE FROM exercise_alternatives WHERE id NOT IN (SELECT MIN(id) FROM exercise_alternatives GROUP BY exerciseId, alternativeExerciseId, type)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_exercise_alternatives_exerciseId_alternativeExerciseId_type ON exercise_alternatives(exerciseId, alternativeExerciseId, type)")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
                    MIGRATION_12_13
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
