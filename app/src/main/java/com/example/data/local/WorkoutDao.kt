package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    
    // Checkpoints Sincronização
    @Query("SELECT * FROM exercise_sync_checkpoints")
    suspend fun getAllSyncCheckpoints(): List<ExerciseSyncCheckpointEntity>

    @Query("SELECT * FROM exercise_sync_checkpoints WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')")
    suspend fun getPendingSyncCheckpoints(): List<ExerciseSyncCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncCheckpoints(checkpoints: List<ExerciseSyncCheckpointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSyncCheckpoint(checkpoint: ExerciseSyncCheckpointEntity)

    @Query("DELETE FROM exercise_sync_checkpoints")
    suspend fun clearSyncCheckpoints()

    // Premium Entities
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseEducation(entity: ExerciseEducationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseMedia(entity: ExerciseMediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseProgression(entity: ExerciseProgressionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseSafety(entity: ExerciseSafetyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseSubstitutionPremium(entity: ExerciseSubstitutionPremiumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseAiContext(entity: ExerciseAiContextEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseBiomechanics(entity: ExerciseBiomechanicsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseExecution(entity: ExerciseExecutionEntity)

    @Query("SELECT * FROM exercise_education WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseEducation(exerciseId: Long): ExerciseEducationEntity?

    @Query("SELECT * FROM exercise_media WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseMedia(exerciseId: Long): ExerciseMediaEntity?

    @Query("SELECT * FROM exercise_progression WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseProgression(exerciseId: Long): ExerciseProgressionEntity?

    @Query("SELECT * FROM exercise_safety WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseSafety(exerciseId: Long): ExerciseSafetyEntity?

    @Query("SELECT * FROM exercise_substitutions WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseSubstitutionPremium(exerciseId: Long): ExerciseSubstitutionPremiumEntity?

    @Query("SELECT * FROM exercise_ai_context WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseAiContext(exerciseId: Long): ExerciseAiContextEntity?

    @Query("SELECT * FROM exercise_biomechanics WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseBiomechanics(exerciseId: Long): ExerciseBiomechanicsEntity?

    @Query("SELECT * FROM exercise_execution WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getExerciseExecution(exerciseId: Long): ExerciseExecutionEntity?

    // Exercises
    @Query("SELECT * FROM exercises WHERE active = 1 ORDER BY name ASC")
    fun getActiveExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE active = 1 ORDER BY name ASC")
    suspend fun getAllExercisesList(): List<ExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercise(exercise: ExerciseEntity): Long

    @Update
    suspend fun updateExercise(exercise: ExerciseEntity)

    @Delete
    suspend fun deleteExercise(exercise: ExerciseEntity)

    @Query("SELECT * FROM exercises WHERE canonicalId = :canonicalId LIMIT 1")
    suspend fun getExerciseByCanonicalId(canonicalId: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE name = :name LIMIT 1")
    suspend fun getExerciseByName(name: String): ExerciseEntity?

    @Query("SELECT * FROM exercises ORDER BY name ASC")
    suspend fun getAllExercisesSync(): List<ExerciseEntity>

    // Programs

    @Query("SELECT * FROM workout_programs WHERE externalId = :externalId LIMIT 1")
    suspend fun getProgramByExternalId(externalId: String): WorkoutProgramEntity?

    @Query("SELECT * FROM workout_programs ORDER BY id DESC")

    fun getAllPrograms(): Flow<List<WorkoutProgramEntity>>



    @Query("SELECT * FROM workout_programs ORDER BY id DESC")

    suspend fun getAllProgramsSync(): List<WorkoutProgramEntity>

    @Query("SELECT * FROM workout_programs WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentProgram(): Flow<WorkoutProgramEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgram(program: WorkoutProgramEntity): Long

    @Update
    suspend fun updateProgram(program: WorkoutProgramEntity)

    @Query("UPDATE workout_programs SET isCurrent = 0")
    suspend fun clearCurrentProgram()

    @Query("UPDATE workout_programs SET isCurrent = 1 WHERE id = :id")
    suspend fun setCurrentProgram(id: Long)

    // Templates
    @Query("SELECT * FROM workout_templates WHERE programId = :programId ORDER BY orderInProgram ASC")
    fun getTemplatesForProgram(programId: Long): Flow<List<WorkoutTemplateEntity>>

    @Query("SELECT * FROM workout_templates WHERE programId = :programId ORDER BY orderInProgram ASC")
    suspend fun getTemplatesForProgramSync(programId: Long): List<WorkoutTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: WorkoutTemplateEntity): Long

    @Update
    suspend fun updateTemplate(template: WorkoutTemplateEntity)

    @Query("DELETE FROM workout_template_exercises WHERE templateId = :templateId")
    suspend fun deleteTemplateExercisesForTemplate(templateId: Long)

    // Sessions (for history and next workout logic)
    @Query("SELECT * FROM workout_sessions WHERE status = 'COMPLETED' ORDER BY finishedAt DESC LIMIT 1")
    suspend fun getLastCompletedSession(): WorkoutSessionEntity?

    @Query("SELECT COUNT(*) FROM workout_sessions WHERE status = 'COMPLETED' AND finishedAt >= :startOfWeek")
    fun getWeeklyCompletedSessionsCount(startOfWeek: Long): Flow<Int>

    @Query("SELECT * FROM workout_sessions WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    fun getActiveSessionFlow(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSession(): WorkoutSessionEntity?

    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity): Long

    @Update
    suspend fun updateSession(session: WorkoutSessionEntity)

    @Insert
    suspend fun insertExerciseSession(exerciseSession: ExerciseSessionEntity): Long

    @Insert
    suspend fun insertSetLogs(setLogs: List<SetLogEntity>)

    @Transaction
    @Query("SELECT * FROM workout_sessions WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    fun getActiveSessionWithDetailsFlow(): Flow<SessionWithDetails?>

    @Query("""
        SELECT sl.* FROM set_logs sl
        WHERE sl.completed = 1 AND sl.exerciseSessionId = (
            SELECT es.id FROM exercise_sessions es
            INNER JOIN workout_sessions ws ON es.sessionId = ws.id
            WHERE ws.status = 'COMPLETED'
              AND (es.actualExerciseId = :exerciseId OR es.plannedExerciseId = :exerciseId)
            ORDER BY ws.finishedAt DESC, es.sortOrder DESC, es.id DESC
            LIMIT 1
        )
        ORDER BY sl.setNumber ASC
    """)
    suspend fun getLastExecutionSetsForExercise(exerciseId: Long): List<SetLogEntity>

    @Query("SELECT * FROM set_logs WHERE exerciseSessionId = :exerciseSessionId ORDER BY setNumber ASC")
    suspend fun getSetLogsForExerciseSession(exerciseSessionId: Long): List<SetLogEntity>

    @Update
    suspend fun updateSetLog(setLog: SetLogEntity)

    @Update
    suspend fun updateSetLogs(setLogs: List<SetLogEntity>)

    @Delete
    suspend fun deleteSetLog(setLog: SetLogEntity)

    @Delete
    suspend fun deleteProgram(program: WorkoutProgramEntity)

    @Delete
    suspend fun deleteTemplate(template: WorkoutTemplateEntity)

    @Query("SELECT * FROM workout_templates WHERE id = :templateId LIMIT 1")
    suspend fun getTemplateById(templateId: Long): WorkoutTemplateEntity?

    // Phase 11: Personal Records
    @Insert
    suspend fun insertPersonalRecord(pr: PersonalRecordEntity)

    @Query("SELECT * FROM personal_records WHERE exerciseId = :exerciseId AND prType = :prType ORDER BY value DESC LIMIT 1")
    suspend fun getHighestPR(exerciseId: Long, prType: String): PersonalRecordEntity?

    @Query("SELECT * FROM personal_records WHERE exerciseId = :exerciseId ORDER BY date DESC")
    fun getPRsForExerciseFlow(exerciseId: Long): Flow<List<PersonalRecordEntity>>

    @Query("SELECT * FROM personal_records ORDER BY date DESC LIMIT 5")
    fun getRecentPRsFlow(): Flow<List<PersonalRecordEntity>>

    // Phase 9: Check-in and Calendar
    @Insert
    suspend fun insertCheckIn(checkIn: CheckInEntity): Long

    @Update
    suspend fun updateCheckIn(checkIn: CheckInEntity)

    @Delete
    suspend fun deleteCheckIn(checkIn: CheckInEntity)

    @Query("SELECT * FROM check_ins WHERE checkOutTime IS NULL ORDER BY checkInTime DESC LIMIT 1")
    suspend fun getActiveCheckIn(): CheckInEntity?

    @Query("SELECT * FROM check_ins WHERE checkOutTime IS NULL ORDER BY checkInTime DESC LIMIT 1")
    fun getActiveCheckInFlow(): Flow<CheckInEntity?>

    @Query("SELECT * FROM check_ins WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getCheckInForSession(sessionId: Long): CheckInEntity?

    @Transaction
    @Query("SELECT * FROM workout_sessions WHERE status = 'COMPLETED' ORDER BY startedAt DESC")
    fun getAllCompletedSessionsWithDetailsFlow(): Flow<List<SessionCalendarSummary>>

    @Transaction
    @Query("SELECT * FROM workout_sessions WHERE status = 'COMPLETED' ORDER BY startedAt DESC")
    suspend fun getAllCompletedSessionsWithDetails(): List<SessionCalendarSummary>

    @Delete
    suspend fun deleteWorkoutSession(session: WorkoutSessionEntity)

    // Phase 8: Alternatives and Execution Edits
    @Query("SELECT * FROM exercise_alternatives WHERE exerciseId = :exerciseId AND alternativeExerciseId != :exerciseId")
    suspend fun getAlternativesForExercise(exerciseId: Long): List<ExerciseAlternativeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlternative(alt: ExerciseAlternativeEntity): Long

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    suspend fun getExerciseById(id: Long): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    fun getExerciseByIdFlow(id: Long): Flow<ExerciseEntity?>

    // Exercise User Overrides
    @Query("SELECT * FROM exercise_user_overrides WHERE exerciseId = :exerciseId LIMIT 1")
    fun getOverrideForExerciseFlow(exerciseId: Long): Flow<ExerciseUserOverrideEntity?>

    @Query("SELECT * FROM exercise_user_overrides WHERE exerciseId = :exerciseId LIMIT 1")
    suspend fun getOverrideForExercise(exerciseId: Long): ExerciseUserOverrideEntity?

    @Query("SELECT * FROM exercise_user_overrides")
    suspend fun getAllOverrides(): List<ExerciseUserOverrideEntity>

    @Query("SELECT * FROM exercise_user_overrides")
    fun getAllOverridesFlow(): Flow<List<ExerciseUserOverrideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateOverride(override: ExerciseUserOverrideEntity)

    @Delete
    suspend fun deleteOverride(override: ExerciseUserOverrideEntity)

    @Query("SELECT MAX(contentVersion) FROM exercises")
    suspend fun getMaxContentVersion(): Int?

    @Query("SELECT COUNT(*) FROM exercises WHERE canonicalId IS NOT NULL AND TRIM(canonicalId) != ''")
    suspend fun getCanonicalExercisesCount(): Int

    @Query("SELECT * FROM exercise_sessions WHERE id = :id LIMIT 1")
    suspend fun getExerciseSessionById(id: Long): ExerciseSessionEntity?

    @Query("""
        SELECT e.* FROM exercises e 
        INNER JOIN exercise_alternatives ea ON e.id = ea.alternativeExerciseId 
        WHERE ea.exerciseId = :exerciseId 
          AND ea.alternativeExerciseId != :exerciseId 
          AND e.id != :exerciseId
          AND e.active = 1
    """)
    suspend fun getExplicitAlternatives(exerciseId: Long): List<ExerciseEntity>

    @Query("""
        SELECT * FROM exercises 
        WHERE substitutionGroup IS NOT NULL 
          AND substitutionGroup = (SELECT substitutionGroup FROM exercises WHERE id = :exerciseId) 
          AND id != :exerciseId 
          AND active = 1
    """)
    suspend fun getAlternativesBySubstitutionGroup(exerciseId: Long): List<ExerciseEntity>

    @Query("""
        SELECT * FROM exercises 
        WHERE movementPattern IS NOT NULL 
          AND movementPattern = (SELECT movementPattern FROM exercises WHERE id = :exerciseId) 
          AND id != :exerciseId 
          AND active = 1
    """)
    suspend fun getAlternativesByMovementPattern(exerciseId: Long): List<ExerciseEntity>

    @Query("""
        SELECT * FROM exercises 
        WHERE primaryMuscle IS NOT NULL 
          AND primaryMuscle = (SELECT primaryMuscle FROM exercises WHERE id = :exerciseId) 
          AND id != :exerciseId 
          AND active = 1
    """)
    suspend fun getAlternativesByMuscle(exerciseId: Long): List<ExerciseEntity>

    @Query("""
        UPDATE exercise_sessions 
        SET actualExerciseId = :newExerciseId, 
            exerciseNameSnapshot = :newName, 
            replacementReason = :reason 
        WHERE id = :exerciseSessionId
    """)
    suspend fun updateExerciseSessionActualExercise(exerciseSessionId: Long, newExerciseId: Long, newName: String, reason: String)

    @Query("""
        UPDATE workout_template_exercises 
        SET exerciseId = :newExerciseId 
        WHERE templateId = :templateId AND exerciseId = :oldExerciseId
    """)
    suspend fun updateTemplateExercise(templateId: Long, oldExerciseId: Long, newExerciseId: Long)

    @Transaction
    @Query("SELECT * FROM workout_template_exercises WHERE templateId = :templateId ORDER BY sortOrder ASC")
    suspend fun getTemplateExercisesWithDetails(templateId: Long): List<TemplateExerciseWithDetails>

    @Transaction
    @Query("SELECT * FROM workout_template_exercises WHERE templateId = :templateId ORDER BY sortOrder ASC")
    fun getTemplateExercisesWithDetailsFlow(templateId: Long): Flow<List<TemplateExerciseWithDetails>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity)

    @Update
    suspend fun updateTemplateExerciseFull(templateExercise: WorkoutTemplateExerciseEntity)

    @Query("SELECT * FROM workout_templates")
    suspend fun getAllTemplatesSync(): List<WorkoutTemplateEntity>

    @Query("SELECT * FROM workout_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): WorkoutSessionEntity?

    @Query("SELECT * FROM exercise_sessions WHERE sessionId = :sessionId")
    suspend fun getExerciseSessionsForSession(sessionId: Long): List<ExerciseSessionEntity>

    @Delete
    suspend fun deleteTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity)
}

data class TemplateExerciseWithDetails(
    @Embedded val templateExercise: WorkoutTemplateExerciseEntity,
    @Relation(
        parentColumn = "exerciseId",
        entityColumn = "id"
    )
    val exercise: ExerciseEntity
)

data class ExerciseSessionWithSets(
    @Embedded val exerciseSession: ExerciseSessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "exerciseSessionId"
    )
    val sets: List<SetLogEntity>
)

data class SessionWithDetails(
    @Embedded val session: WorkoutSessionEntity,
    @Relation(
        entity = ExerciseSessionEntity::class,
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val exercises: List<ExerciseSessionWithSets>
)

data class SessionCalendarSummary(
    @Embedded val session: WorkoutSessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val checkIn: CheckInEntity?,
    @Relation(
        entity = ExerciseSessionEntity::class,
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val exercises: List<ExerciseSessionWithSets>
)

