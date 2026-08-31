with open('app/src/test/java/com/example/FakeWorkoutDao.kt', 'r') as f:
    lines = f.readlines()

end_idx = -1
for i, line in enumerate(lines):
    if line.startswith("    override suspend fun getExerciseSessionsForSession(sessionId: Long): List<ExerciseSessionEntity> = exerciseSessions.filter { it.sessionId == sessionId }"):
        end_idx = i
        break

if end_idx != -1:
    with open('app/src/test/java/com/example/FakeWorkoutDao.kt', 'w') as f:
        for i in range(end_idx + 1):
            f.write(lines[i])
        f.write("\n")
        f.write("    override suspend fun insertExerciseEducation(entity: ExerciseEducationEntity) {}\n")
        f.write("    override suspend fun insertExerciseMedia(entity: ExerciseMediaEntity) {}\n")
        f.write("    override suspend fun insertExerciseProgression(entity: ExerciseProgressionEntity) {}\n")
        f.write("    override suspend fun insertExerciseSafety(entity: ExerciseSafetyEntity) {}\n")
        f.write("    override suspend fun insertExerciseSubstitutionPremium(entity: ExerciseSubstitutionPremiumEntity) {}\n")
        f.write("    override suspend fun insertExerciseAiContext(entity: ExerciseAiContextEntity) {}\n")
        f.write("    override suspend fun insertExerciseBiomechanics(entity: ExerciseBiomechanicsEntity) {}\n")
        f.write("    override suspend fun insertExerciseExecution(entity: ExerciseExecutionEntity) {}\n")
        f.write("\n")
        f.write("    override suspend fun getExerciseEducation(exerciseId: Long): ExerciseEducationEntity? = null\n")
        f.write("    override suspend fun getExerciseMedia(exerciseId: Long): ExerciseMediaEntity? = null\n")
        f.write("    override suspend fun getExerciseProgression(exerciseId: Long): ExerciseProgressionEntity? = null\n")
        f.write("    override suspend fun getExerciseSafety(exerciseId: Long): ExerciseSafetyEntity? = null\n")
        f.write("    override suspend fun getExerciseSubstitutionPremium(exerciseId: Long): ExerciseSubstitutionPremiumEntity? = null\n")
        f.write("    override suspend fun getExerciseAiContext(exerciseId: Long): ExerciseAiContextEntity? = null\n")
        f.write("    override suspend fun getExerciseBiomechanics(exerciseId: Long): ExerciseBiomechanicsEntity? = null\n")
        f.write("    override suspend fun getExerciseExecution(exerciseId: Long): ExerciseExecutionEntity? = null\n")
        f.write("}\n")

