package com.example.domain.workout.template

import com.example.data.local.WorkoutTemplateExerciseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `sortOrder` é posição, não identidade (T19.6 §6–§7): reordenar só muda posição.
 */
class TemplateExerciseOrderTest {

    private fun item(id: Long, sortOrder: Int, exerciseId: Long = id * 10) = WorkoutTemplateExerciseEntity(
        id = id,
        templateId = 1L,
        exerciseId = exerciseId,
        sortOrder = sortOrder,
        targetSets = 4,
        minReps = 8,
        maxReps = 12,
        restDurationSeconds = 90,
        plannedWeight = 60f,
        machineLabel = "Máquina $id",
        notes = "obs $id"
    )

    // Supino (1), Crucifixo (2), Tríceps (3)
    private val current = listOf(item(1, 0), item(2, 1), item(3, 2))

    private fun ids(list: List<WorkoutTemplateExerciseEntity>) = list.map { it.id }
    private fun orders(list: List<WorkoutTemplateExerciseEntity>) = list.map { it.sortOrder }

    @Test
    fun `ultimo para primeiro - o exemplo do enunciado`() {
        val result = TemplateExerciseOrder.reorder(current, listOf(3L, 1L, 2L))

        assertEquals(listOf(3L, 1L, 2L), ids(result))
        assertEquals(listOf(0, 1, 2), orders(result))
    }

    @Test
    fun `move para cima e para baixo por indice`() {
        assertEquals(listOf(1L, 3L, 2L), ids(TemplateExerciseOrder.move(current, 2, 1)))
        assertEquals(listOf(2L, 1L, 3L), ids(TemplateExerciseOrder.move(current, 0, 1)))
        assertEquals(listOf(2L, 3L, 1L), ids(TemplateExerciseOrder.move(current, 0, 2)))
        assertEquals(listOf(3L, 1L, 2L), ids(TemplateExerciseOrder.move(current, 2, 0)))
    }

    @Test
    fun `indice invalido nao muda nada`() {
        assertSame(current, TemplateExerciseOrder.move(current, 0, 3))
        assertSame(current, TemplateExerciseOrder.move(current, -1, 0))
    }

    @Test
    fun `reordenar preserva id, exerciseId e toda a configuracao`() {
        val result = TemplateExerciseOrder.reorder(current, listOf(3L, 1L, 2L))

        result.forEach { moved ->
            val original = current.single { it.id == moved.id }
            assertEquals(original.copy(sortOrder = moved.sortOrder), moved)
        }
        assertEquals(current.size, result.size)
        assertEquals(current.map { it.id }.toSet(), result.map { it.id }.toSet())
    }

    @Test
    fun `normaliza buracos e repeticoes do sortOrder`() {
        val sparse = listOf(item(1, 0), item(2, 0), item(3, 7))

        assertEquals(listOf(0, 1, 2), orders(TemplateExerciseOrder.normalize(sparse)))
        assertEquals(listOf(0, 1, 2), orders(TemplateExerciseOrder.reorder(sparse, listOf(1L, 2L, 3L))))
    }

    @Test
    fun `idempotente - aplicar duas vezes da o mesmo resultado`() {
        val once = TemplateExerciseOrder.reorder(current, listOf(3L, 1L, 2L))
        val twice = TemplateExerciseOrder.reorder(once, listOf(3L, 1L, 2L))

        assertEquals(once, twice)
        assertEquals(current, TemplateExerciseOrder.reorder(current, listOf(1L, 2L, 3L)))
    }

    @Test
    fun `ids desconhecidos sao ignorados e os ausentes vao para o fim - sem duplicar nem remover`() {
        // Um sync recriou a linha 2 como 9 enquanto o usuário arrastava (T16.3).
        val afterSync = listOf(item(1, 0), item(9, 1), item(3, 2))

        val result = TemplateExerciseOrder.reorder(afterSync, listOf(3L, 2L, 1L, 3L))

        assertEquals(listOf(3L, 1L, 9L), ids(result))
        assertEquals(listOf(0, 1, 2), orders(result))
    }

    @Test
    fun `lista vazia continua vazia`() {
        assertEquals(emptyList<WorkoutTemplateExerciseEntity>(), TemplateExerciseOrder.reorder(emptyList(), listOf(1L)))
    }
}
