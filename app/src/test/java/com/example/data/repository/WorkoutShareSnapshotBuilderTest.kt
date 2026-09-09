package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutShareSnapshotBuilderTest {

    private val builder = WorkoutShareSnapshotBuilder()

    private val sampleTemplate = WorkoutTemplateEntity(
        id = 42L,
        programId = 1L,
        name = "Treino A - Peito e Tríceps",
        shortIdentifier = "A",
        orderInProgram = 0,
        syncId = "tmpl-sync-12345"
    )

    private fun createExercise(
        id: Long,
        name: String,
        canonicalId: String?,
        isUserCreated: Boolean = false
    ) = ExerciseEntity(
        id = id,
        name = name,
        canonicalId = canonicalId,
        isUserCreated = isUserCreated
    )

    private fun createTemplateExercise(
        exerciseId: Long,
        sortOrder: Int,
        targetSets: Int = 4,
        minReps: Int = 8,
        maxReps: Int = 12,
        restDurationSeconds: Int = 90,
        plannedWeight: Float? = 80.0f,
        machineLabel: String? = "Banco 3",
        notes: String? = "Aumentar 2kg próxima semana"
    ) = WorkoutTemplateExerciseEntity(
        id = 100L + sortOrder,
        templateId = 42L,
        exerciseId = exerciseId,
        sortOrder = sortOrder,
        targetSets = targetSets,
        minReps = minReps,
        maxReps = maxReps,
        restDurationSeconds = restDurationSeconds,
        plannedWeight = plannedWeight,
        machineLabel = machineLabel,
        notes = notes
    )

    @Test
    fun `buildSnapshot succeeds with canonical catalog exercises and preserves sort order`() {
        val ex1 = createExercise(1L, "Supino Reto com Barra", "catalog-bench-press")
        val ex2 = createExercise(2L, "Tríceps Corda", "catalog-triceps-rope")

        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(2L, sortOrder = 1), ex2),
            TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), ex1)
        )

        val result = builder.buildSnapshot(sampleTemplate, details)

        assertTrue("Deveria construir snapshot com sucesso", result is SnapshotBuildResult.Success)
        val snapshot = (result as SnapshotBuildResult.Success).snapshot

        assertEquals(1, snapshot.snapshotVersion)
        assertEquals("Treino A - Peito e Tríceps", snapshot.name)
        assertEquals("A", snapshot.shortIdentifier)
        assertEquals(2, snapshot.exercises.size)

        // Verificação da ordenação
        assertEquals("catalog-bench-press", snapshot.exercises[0].canonicalExerciseId)
        assertEquals(0, snapshot.exercises[0].sortOrder)
        assertEquals("catalog-triceps-rope", snapshot.exercises[1].canonicalExerciseId)
        assertEquals(1, snapshot.exercises[1].sortOrder)
    }

    @Test
    fun `buildSnapshot omits private fields plannedWeight, notes and local IDs from snapshot`() {
        val ex1 = createExercise(1L, "Supino Reto com Barra", "catalog-bench-press")
        val details = listOf(
            TemplateExerciseWithDetails(
                createTemplateExercise(
                    exerciseId = 1L,
                    sortOrder = 0,
                    plannedWeight = 100.0f,
                    machineLabel = "Máquina 4",
                    notes = "Pegada fechada"
                ),
                ex1
            )
        )

        val result = builder.buildSnapshot(sampleTemplate, details)
        assertTrue(result is SnapshotBuildResult.Success)
        val snapshot = (result as SnapshotBuildResult.Success).snapshot

        val sharedEx = snapshot.exercises.first()
        assertEquals("catalog-bench-press", sharedEx.canonicalExerciseId)
        assertEquals(4, sharedEx.targetSets)
        assertEquals(8, sharedEx.minReps)
        assertEquals(12, sharedEx.maxReps)
        assertEquals(90, sharedEx.restDurationSeconds)
    }

    @Test
    fun `buildSnapshot blocks when template has no exercises`() {
        val result = builder.buildSnapshot(sampleTemplate, emptyList())
        assertTrue("Treino sem exercícios deve ser bloqueado", result is SnapshotBuildResult.Blocked)
    }

    @Test
    fun `buildSnapshot blocks when exercise is marked as isUserCreated`() {
        val ex1 = createExercise(1L, "Meu Supino Especial", "custom-id-123", isUserCreated = true)
        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), ex1)
        )

        val result = builder.buildSnapshot(sampleTemplate, details)
        assertTrue("Exercício criado pelo usuário deve ser bloqueado", result is SnapshotBuildResult.Blocked)
        val blocked = result as SnapshotBuildResult.Blocked
        assertTrue(blocked.reasons.any { it.contains("Meu Supino Especial") })
    }

    @Test
    fun `buildSnapshot blocks when exercise has null or blank canonicalId`() {
        val ex1 = createExercise(1L, "Exercício Sem Catálogo", canonicalId = null, isUserCreated = false)
        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), ex1)
        )

        val result = builder.buildSnapshot(sampleTemplate, details)
        assertTrue("Exercício sem canonicalId deve ser bloqueado", result is SnapshotBuildResult.Blocked)
        val blocked = result as SnapshotBuildResult.Blocked
        assertTrue(blocked.reasons.any { it.contains("Exercício Sem Catálogo") })
    }
}
