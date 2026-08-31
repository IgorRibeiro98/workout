import re

with open('app/src/main/java/com/example/data/local/AppDatabase.kt', 'r') as f:
    content = f.read()

# Add entities
content = content.replace(
    'ExerciseUserOverrideEntity::class\n    ],',
    'ExerciseUserOverrideEntity::class,\n        ExerciseEducationEntity::class,\n        ExerciseMediaEntity::class,\n        ExerciseProgressionEntity::class,\n        ExerciseSafetyEntity::class,\n        ExerciseSubstitutionPremiumEntity::class,\n        ExerciseAiContextEntity::class,\n        ExerciseBiomechanicsEntity::class,\n        ExerciseExecutionEntity::class\n    ],'
)

# Bump version
content = content.replace('version = 18,', 'version = 19,')

# Add migration
migration_18_19 = """
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
"""
content = content.replace('companion object {', 'companion object {' + migration_18_19)

# add to fallbackToDestructiveMigration fallback
content = content.replace(
    'addMigrations(MIGRATION_17_18, MIGRATION_16_17,',
    'addMigrations(MIGRATION_18_19, MIGRATION_17_18, MIGRATION_16_17,'
)

with open('app/src/main/java/com/example/data/local/AppDatabase.kt', 'w') as f:
    f.write(content)
