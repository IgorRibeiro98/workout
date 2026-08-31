import re

with open('app/src/test/java/com/example/FakeWorkoutDao.kt', 'r') as f:
    content = f.read()

# I will find the last occurrence of getExerciseById since it's around the end of the original file
match = re.search(r'override suspend fun getExerciseById\(id: Long\): ExerciseEntity\? \{.*?\n    \}', content, re.DOTALL)
if match:
    index = match.end()
    # truncate everything after that
    base_content = content[:index]

    new_methods = """
    
    override suspend fun insertExerciseEducation(entity: ExerciseEducationEntity) {}
    override suspend fun insertExerciseMedia(entity: ExerciseMediaEntity) {}
    override suspend fun insertExerciseProgression(entity: ExerciseProgressionEntity) {}
    override suspend fun insertExerciseSafety(entity: ExerciseSafetyEntity) {}
    override suspend fun insertExerciseSubstitutionPremium(entity: ExerciseSubstitutionPremiumEntity) {}
    override suspend fun insertExerciseAiContext(entity: ExerciseAiContextEntity) {}
    override suspend fun insertExerciseBiomechanics(entity: ExerciseBiomechanicsEntity) {}
    override suspend fun insertExerciseExecution(entity: ExerciseExecutionEntity) {}
    
    override suspend fun getExerciseEducation(exerciseId: Long): ExerciseEducationEntity? = null
    override suspend fun getExerciseMedia(exerciseId: Long): ExerciseMediaEntity? = null
    override suspend fun getExerciseProgression(exerciseId: Long): ExerciseProgressionEntity? = null
    override suspend fun getExerciseSafety(exerciseId: Long): ExerciseSafetyEntity? = null
    override suspend fun getExerciseSubstitutionPremium(exerciseId: Long): ExerciseSubstitutionPremiumEntity? = null
    override suspend fun getExerciseAiContext(exerciseId: Long): ExerciseAiContextEntity? = null
    override suspend fun getExerciseBiomechanics(exerciseId: Long): ExerciseBiomechanicsEntity? = null
    override suspend fun getExerciseExecution(exerciseId: Long): ExerciseExecutionEntity? = null
}
"""
    with open('app/src/test/java/com/example/FakeWorkoutDao.kt', 'w') as f:
        f.write(base_content + new_methods)

