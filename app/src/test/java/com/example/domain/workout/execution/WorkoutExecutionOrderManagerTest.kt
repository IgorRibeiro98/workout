package com.example.domain.workout.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutExecutionOrderManagerTest {

    @Test
    fun testMovePendingExercise() {
        // Teste 1 — Mover exercício pendente: Entrada A, B, C -> Mover A para 3 -> Resultado B, C, A
        val executions = listOf(
            WorkoutExerciseExecution(exerciseId = "A", name = "A", plannedOrder = 1, executionOrder = 1, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "B", name = "B", plannedOrder = 2, executionOrder = 2, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "C", name = "C", plannedOrder = 3, executionOrder = 3, status = ExerciseExecutionStatus.PENDING)
        )

        val result = WorkoutExecutionOrderManager.moveExercise(executions, "A", 3)

        assertEquals(3, result.size)
        assertEquals("B", result[0].exerciseId)
        assertEquals(1, result[0].executionOrder)
        assertEquals("C", result[1].exerciseId)
        assertEquals(2, result[1].executionOrder)
        assertEquals("A", result[2].exerciseId)
        assertEquals(3, result[2].executionOrder)
    }

    @Test
    fun testCompletedExerciseRequiresConfirmation() {
        // Teste 2 — Exercício concluído: Entrada A COMPLETED, B PENDING -> requiresMoveConfirmation = true
        val exerciseA = WorkoutExerciseExecution(exerciseId = "A", name = "A", plannedOrder = 1, executionOrder = 1, status = ExerciseExecutionStatus.COMPLETED)
        val exerciseB = WorkoutExerciseExecution(exerciseId = "B", name = "B", plannedOrder = 2, executionOrder = 2, status = ExerciseExecutionStatus.PENDING)

        assertTrue(WorkoutExecutionOrderManager.requiresMoveConfirmation(exerciseA))
        assertFalse(WorkoutExecutionOrderManager.canMoveExercise(exerciseA))

        assertFalse(WorkoutExecutionOrderManager.requiresMoveConfirmation(exerciseB))
        assertTrue(WorkoutExecutionOrderManager.canMoveExercise(exerciseB))
    }

    @Test
    fun testDoNotAlterPlannedOrder() {
        // Teste 3 — Não alterar plannedOrder: Entrada A plannedOrder=1, B plannedOrder=2 -> Mover B -> 1
        val executions = listOf(
            WorkoutExerciseExecution(exerciseId = "A", name = "A", plannedOrder = 1, executionOrder = 1, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "B", name = "B", plannedOrder = 2, executionOrder = 2, status = ExerciseExecutionStatus.PENDING)
        )

        val result = WorkoutExecutionOrderManager.moveExercise(executions, "B", 1)

        assertEquals("B", result[0].exerciseId)
        assertEquals(1, result[0].executionOrder)
        assertEquals(2, result[0].plannedOrder) // Planned order remains unchanged!

        assertEquals("A", result[1].exerciseId)
        assertEquals(2, result[1].executionOrder)
        assertEquals(1, result[1].plannedOrder) // Planned order remains unchanged!
    }

    @Test
    fun testIsOrderAdapted() {
        // Teste 4 — Ordem adaptada: Planejado A, B, C; Executado B, A, C -> wasOrderAdapted = true
        val adaptedExecutions = listOf(
            WorkoutExerciseExecution(exerciseId = "B", name = "B", plannedOrder = 2, executionOrder = 1, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "A", name = "A", plannedOrder = 1, executionOrder = 2, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "C", name = "C", plannedOrder = 3, executionOrder = 3, status = ExerciseExecutionStatus.PENDING)
        )

        assertTrue(WorkoutExecutionOrderManager.isOrderAdapted(adaptedExecutions))

        val normalExecutions = listOf(
            WorkoutExerciseExecution(exerciseId = "A", name = "A", plannedOrder = 1, executionOrder = 1, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "B", name = "B", plannedOrder = 2, executionOrder = 2, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "C", name = "C", plannedOrder = 3, executionOrder = 3, status = ExerciseExecutionStatus.PENDING)
        )

        assertFalse(WorkoutExecutionOrderManager.isOrderAdapted(normalExecutions))
    }

    @Test
    fun testMoveLastExerciseToLaterProducesNoChange() {
        // Teste 5 — Último exercício: Entrada A, B, C -> Mover C para depois -> Esperado: Nenhuma alteração
        val executions = listOf(
            WorkoutExerciseExecution(exerciseId = "A", name = "A", plannedOrder = 1, executionOrder = 1, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "B", name = "B", plannedOrder = 2, executionOrder = 2, status = ExerciseExecutionStatus.PENDING),
            WorkoutExerciseExecution(exerciseId = "C", name = "C", plannedOrder = 3, executionOrder = 3, status = ExerciseExecutionStatus.PENDING)
        )

        val result = WorkoutExecutionOrderManager.moveExerciseToLater(executions, "C")

        assertEquals(executions, result)
    }
}
