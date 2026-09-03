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

    // T12.6.7 — a reordenação precisa sobreviver ao ciclo inteiro: planejamento, execução e
    // alterações feitas no meio do treino.

    private fun pending(id: String, planned: Int, execution: Int) = WorkoutExerciseExecution(
        exerciseId = id,
        name = id,
        plannedOrder = planned,
        executionOrder = execution,
        status = ExerciseExecutionStatus.PENDING
    )

    @Test
    fun testReorderIsIdempotentAndAlwaysContiguous() {
        // A ordem persistida precisa ser 1..N sem buracos, senão a reidratação depois de fechar o
        // app reconstrói uma sequência diferente da que o usuário salvou.
        val executions = listOf(pending("A", 1, 1), pending("B", 2, 2), pending("C", 3, 3))

        val once = WorkoutExecutionOrderManager.moveExercise(executions, "C", 1)
        assertEquals(listOf("C", "A", "B"), once.map { it.exerciseId })
        assertEquals(listOf(1, 2, 3), once.map { it.executionOrder })

        // Reaplicar o mesmo movimento sobre o resultado já persistido não muda nada.
        val twice = WorkoutExecutionOrderManager.moveExercise(once, "C", 1)
        assertEquals(once, twice)
    }

    @Test
    fun testMoveKeepsEveryExerciseExactlyOnce() {
        // Exercícios adicionados durante o treino entram no fim; nenhum pode sumir ou duplicar.
        val executions = listOf(
            pending("A", 1, 1),
            pending("B", 2, 2),
            pending("C", 3, 3),
            pending("Adicionado", 4, 4)
        )

        val result = WorkoutExecutionOrderManager.moveExercise(executions, "Adicionado", 2)

        assertEquals(4, result.size)
        assertEquals(executions.map { it.exerciseId }.toSet(), result.map { it.exerciseId }.toSet())
        assertEquals(listOf("A", "Adicionado", "B", "C"), result.map { it.exerciseId })
        assertEquals(listOf(1, 2, 3, 4), result.map { it.executionOrder })
    }

    @Test
    fun testMoveOnRemovedExerciseLeavesListUntouched() {
        // Se o exercício já foi removido da sessão, o pedido de mover é ignorado em vez de
        // renumerar o restante e embaralhar a ordem.
        val executions = listOf(pending("A", 1, 1), pending("B", 2, 2))

        val result = WorkoutExecutionOrderManager.moveExercise(executions, "Removido", 1)

        assertEquals(executions, result)
    }

    @Test
    fun testPositionOutOfRangeIsClamped() {
        val executions = listOf(pending("A", 1, 1), pending("B", 2, 2), pending("C", 3, 3))

        assertEquals(
            listOf("B", "C", "A"),
            WorkoutExecutionOrderManager.moveExercise(executions, "A", 99).map { it.exerciseId }
        )
        assertEquals(
            listOf("C", "A", "B"),
            WorkoutExecutionOrderManager.moveExercise(executions, "C", 0).map { it.exerciseId }
        )
    }

    @Test
    fun testMoveToLaterKeepsCompletedExercisesBehind() {
        // A concluído, B e C pendentes: adiar B deve colocá-lo depois de C, e nunca antes de A.
        val executions = listOf(
            WorkoutExerciseExecution(exerciseId = "A", name = "A", plannedOrder = 1, executionOrder = 1, status = ExerciseExecutionStatus.COMPLETED),
            pending("B", 2, 2),
            pending("C", 3, 3)
        )

        val result = WorkoutExecutionOrderManager.moveExerciseToLater(executions, "B")

        assertEquals(listOf("A", "C", "B"), result.map { it.exerciseId })
        assertEquals(listOf(1, 2, 3), result.map { it.executionOrder })
    }

    @Test
    fun testPlannedOrderSurvivesRepeatedReordering() {
        // plannedOrder é o registro do que foi planejado: nenhuma reordenação pode reescrevê-lo.
        var executions = listOf(pending("A", 1, 1), pending("B", 2, 2), pending("C", 3, 3))

        executions = WorkoutExecutionOrderManager.moveExercise(executions, "C", 1)
        executions = WorkoutExecutionOrderManager.moveExercise(executions, "A", 3)
        executions = WorkoutExecutionOrderManager.moveExerciseToLater(executions, "B")

        val plannedById = executions.associate { it.exerciseId to it.plannedOrder }
        assertEquals(1, plannedById["A"])
        assertEquals(2, plannedById["B"])
        assertEquals(3, plannedById["C"])
        assertTrue(WorkoutExecutionOrderManager.isOrderAdapted(executions))
    }
}
