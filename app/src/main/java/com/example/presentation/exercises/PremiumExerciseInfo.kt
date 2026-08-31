package com.example.presentation.exercises

import com.example.data.local.*

data class PremiumExerciseInfo(
    val education: ExerciseEducationEntity?,
    val media: ExerciseMediaEntity?,
    val progression: ExerciseProgressionEntity?,
    val safety: ExerciseSafetyEntity?,
    val substitution: ExerciseSubstitutionPremiumEntity?,
    val aiContext: ExerciseAiContextEntity?,
    val biomechanics: ExerciseBiomechanicsEntity?,
    val execution: ExerciseExecutionEntity?
)
