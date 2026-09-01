package com.example.domain.workout.execution

data class WorkoutExerciseExecution(
    val exerciseSessionId: Long = 0L,
    val exerciseId: String,
    val name: String,
    val plannedOrder: Int,
    val executionOrder: Int,
    val status: ExerciseExecutionStatus = ExerciseExecutionStatus.PENDING
)
