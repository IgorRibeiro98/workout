package com.example.domain.model

data class ExerciseExecutionValue(
    val mode: ExerciseExecutionMode,
    val value: Int
) {
    val formatted: String
        get() = when (mode) {
            ExerciseExecutionMode.REPS -> "$value reps"
            ExerciseExecutionMode.DURATION -> "${value}s"
        }

    val isDuration: Boolean
        get() = mode == ExerciseExecutionMode.DURATION
}
