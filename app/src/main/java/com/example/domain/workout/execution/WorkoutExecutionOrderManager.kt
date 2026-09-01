package com.example.domain.workout.execution

object WorkoutExecutionOrderManager {

    /**
     * Reorders an exercise identified by [exerciseId] to a new 1-indexed position [newPosition].
     * Positions of all exercises in the list are updated accordingly (executionOrder = 1..N).
     */
    fun moveExercise(
        executions: List<WorkoutExerciseExecution>,
        exerciseId: String,
        newPosition: Int
    ): List<WorkoutExerciseExecution> {
        val index = executions.indexOfFirst { it.exerciseId == exerciseId }
        if (index < 0 || executions.isEmpty()) return executions

        val mutable = executions.toMutableList()
        val item = mutable.removeAt(index)

        // newPosition is 1-indexed in UI (1..N). Target index in zero-based list is coerced.
        val targetIndex = (newPosition - 1).coerceIn(0, mutable.size)
        mutable.add(targetIndex, item)

        return mutable.mapIndexed { pos, ex ->
            ex.copy(executionOrder = pos + 1)
        }
    }

    /**
     * Checks whether an exercise can be moved directly without explicit user confirmation.
     */
    fun canMoveExercise(execution: WorkoutExerciseExecution): Boolean {
        return execution.status != ExerciseExecutionStatus.COMPLETED
    }

    /**
     * Checks if moving this exercise requires user confirmation (e.g., exercise is COMPLETED).
     */
    fun requiresMoveConfirmation(execution: WorkoutExerciseExecution): Boolean {
        return execution.status == ExerciseExecutionStatus.COMPLETED
    }

    /**
     * Checks if moving this exercise requires user confirmation (alias for backward compatibility).
     */
    fun isCompleted(execution: WorkoutExerciseExecution): Boolean {
        return requiresMoveConfirmation(execution)
    }

    /**
     * Moves the exercise identified by [exerciseId] to "later".
     * If there are pending/in-progress exercises after it, moves it right after the last pending/in-progress exercise.
     * If it is already the last exercise, returns the list unchanged.
     */
    fun moveExerciseToLater(
        executions: List<WorkoutExerciseExecution>,
        exerciseId: String
    ): List<WorkoutExerciseExecution> {
        val index = executions.indexOfFirst { it.exerciseId == exerciseId }
        if (index < 0 || index >= executions.size - 1) return executions

        val mutable = executions.toMutableList()
        val item = mutable.removeAt(index)

        // Find last pending or in-progress exercise after index in the remaining items
        val lastPendingIndex = mutable.indexOfLast {
            it.status == ExerciseExecutionStatus.PENDING || it.status == ExerciseExecutionStatus.IN_PROGRESS
        }

        val targetIndex = if (lastPendingIndex >= 0) {
            lastPendingIndex + 1
        } else {
            index.coerceAtMost(mutable.size)
        }

        mutable.add(targetIndex.coerceIn(0, mutable.size), item)

        return mutable.mapIndexed { pos, ex ->
            ex.copy(executionOrder = pos + 1)
        }
    }

    /**
     * Determines whether the current execution sequence differs from the original planned order.
     */
    fun isOrderAdapted(executions: List<WorkoutExerciseExecution>): Boolean {
        return executions.any { it.executionOrder != it.plannedOrder }
    }
}
