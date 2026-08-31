package com.example

object DbHelperV10 {
    val SCHEMA = """
        CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `primaryMuscle` TEXT, `equipment` TEXT, `active` INTEGER NOT NULL, `mediaUrl` TEXT, `rirEnabled` INTEGER NOT NULL, `isBodyweight` INTEGER NOT NULL, `canonicalId` TEXT, `slug` TEXT, `contentVersion` INTEGER NOT NULL, `aliases` TEXT, `nameEn` TEXT, `secondaryMuscles` TEXT, `movementPattern` TEXT, `substitutionGroup` TEXT, `exerciseDbSearch` TEXT, `externalExerciseId` TEXT, `gifUrl` TEXT, `lastVerifiedAt` INTEGER)
        CREATE TABLE IF NOT EXISTS `workout_programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `isCurrent` INTEGER NOT NULL)
        CREATE TABLE IF NOT EXISTS `workout_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `programId` INTEGER NOT NULL, `name` TEXT NOT NULL, `shortIdentifier` TEXT, `orderInProgram` INTEGER NOT NULL, `dayOfWeek` TEXT, FOREIGN KEY(`programId`) REFERENCES `workout_programs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        CREATE TABLE IF NOT EXISTS `workout_template_exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, `minReps` INTEGER NOT NULL, `maxReps` INTEGER NOT NULL, `restDurationSeconds` INTEGER NOT NULL, `plannedWeight` REAL, `machineLabel` TEXT, `notes` TEXT, FOREIGN KEY(`templateId`) REFERENCES `workout_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )
        CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` INTEGER, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, `notes` TEXT, `templateNameSnapshot` TEXT)
        CREATE TABLE IF NOT EXISTS `exercise_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `plannedExerciseId` INTEGER, `actualExerciseId` INTEGER, `exerciseNameSnapshot` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `startedAt` INTEGER, `finishedAt` INTEGER, `notes` TEXT, `replacementReason` TEXT, `machineLabelSnapshot` TEXT, `primaryMuscleSnapshot` TEXT, FOREIGN KEY(`sessionId`) REFERENCES `workout_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        CREATE TABLE IF NOT EXISTS `set_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exerciseSessionId` INTEGER NOT NULL, `setNumber` INTEGER NOT NULL, `type` TEXT NOT NULL, `weight` REAL NOT NULL, `repetitions` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `startedAt` INTEGER, `finishedAt` INTEGER, FOREIGN KEY(`exerciseSessionId`) REFERENCES `exercise_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        CREATE TABLE IF NOT EXISTS `exercise_alternatives` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exerciseId` INTEGER NOT NULL, `alternativeExerciseId` INTEGER NOT NULL, `type` TEXT NOT NULL, FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`alternativeExerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                CREATE TABLE IF NOT EXISTS `check_ins` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `checkInTime` INTEGER NOT NULL, `checkOutTime` INTEGER, `gymName` TEXT, `sessionId` INTEGER, FOREIGN KEY(`sessionId`) REFERENCES `workout_sessions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
        CREATE TABLE IF NOT EXISTS `personal_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exerciseId` INTEGER NOT NULL, `date` INTEGER NOT NULL, `prType` TEXT NOT NULL, `value` REAL NOT NULL, FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
    
        CREATE INDEX IF NOT EXISTS `index_workout_templates_programId` ON `workout_templates` (`programId`)
        CREATE INDEX IF NOT EXISTS `index_workout_template_exercises_templateId` ON `workout_template_exercises` (`templateId`)
        CREATE INDEX IF NOT EXISTS `index_workout_template_exercises_exerciseId` ON `workout_template_exercises` (`exerciseId`)
        CREATE INDEX IF NOT EXISTS `index_exercise_sessions_sessionId` ON `exercise_sessions` (`sessionId`)
        CREATE INDEX IF NOT EXISTS `index_set_logs_exerciseSessionId` ON `set_logs` (`exerciseSessionId`)
        CREATE INDEX IF NOT EXISTS `index_exercise_alternatives_exerciseId` ON `exercise_alternatives` (`exerciseId`)
        CREATE INDEX IF NOT EXISTS `index_exercise_alternatives_alternativeExerciseId` ON `exercise_alternatives` (`alternativeExerciseId`)
        CREATE INDEX IF NOT EXISTS `index_check_ins_sessionId` ON `check_ins` (`sessionId`)
        CREATE INDEX IF NOT EXISTS `index_personal_records_exerciseId` ON `personal_records` (`exerciseId`)
    """.trimIndent()
}
