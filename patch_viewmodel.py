with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsViewModel.kt', 'r') as f:
    content = f.read()

new_method = """
    fun getPremiumInfo(exerciseId: Long): Flow<PremiumExerciseInfo?> = flow {
        val education = workoutDao.getExerciseEducation(exerciseId)
        val media = workoutDao.getExerciseMedia(exerciseId)
        val progression = workoutDao.getExerciseProgression(exerciseId)
        val safety = workoutDao.getExerciseSafety(exerciseId)
        val substitution = workoutDao.getExerciseSubstitutionPremium(exerciseId)
        val aiContext = workoutDao.getExerciseAiContext(exerciseId)
        val biomechanics = workoutDao.getExerciseBiomechanics(exerciseId)
        val execution = workoutDao.getExerciseExecution(exerciseId)
        
        if (education != null || media != null || progression != null || safety != null || substitution != null || aiContext != null || biomechanics != null || execution != null) {
            emit(PremiumExerciseInfo(education, media, progression, safety, substitution, aiContext, biomechanics, execution))
        } else {
            emit(null)
        }
    }
"""

content = content.replace('fun getUserOverride(exerciseId: Long)', new_method + '\n    fun getUserOverride(exerciseId: Long)')

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsViewModel.kt', 'w') as f:
    f.write(content)
