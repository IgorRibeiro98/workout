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
     * Moves the exercise identified by [exerciseId] to "later" (i.e. shifts it down after the next item).
     */
    fun moveExerciseToLater(
        executions: List<WorkoutExerciseExecution>,
        exerciseId: String
    ): List<WorkoutExerciseExecution> {
        val index = executions.indexOfFirst { it.exerciseId == exerciseId }
        if (index < 0 || index >= executions.size - 1) return executions

        val mutable = executions.toMutableList()
        val item = mutable.removeAt(index)
        // Place item right after the next element
        val targetIndex = (index + 1).coerceAtMost(mutable.size)
        mutable.add(targetIndex, item)

        return mutable.mapIndexed { pos, ex ->
            ex.copy(executionOrder = pos + 1)
        }
    }

    /**
     * Checks if moving this exercise requires user confirmation (e.g., exercise is COMPLETED).
     */
    fun isCompleted(execution: WorkoutExerciseExecution): Boolean {
        return execution.status == ExerciseExecutionStatus.COMPLETED
    }

    /**
     * Determines whether the current execution sequence differs from the original planned order.
     */
    fun isOrderAdapted(executions: List<WorkoutExerciseExecution>): Boolean {
        return executions.any { it.executionOrder != it.plannedOrder }
    }
}
