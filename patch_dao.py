with open('app/src/main/java/com/example/data/local/WorkoutDao.kt', 'r') as f:
    content = f.read()

new_methods = """
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
"""

content = content.replace('// Exercises', new_methods + '\n    // Exercises')

with open('app/src/main/java/com/example/data/local/WorkoutDao.kt', 'w') as f:
    f.write(content)
