package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.domain.social.WorkoutShareContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val snapshot = ((result as SnapshotBuildResult.Success).content as WorkoutShareContent.Workout).snapshot

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
        val snapshot = ((result as SnapshotBuildResult.Success).content as WorkoutShareContent.Workout).snapshot

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

    // ------------------------------------------------------------------ programa (T19.3)

    private val sampleProgram = WorkoutProgramEntity(
        id = 7L,
        name = "Push/Pull/Legs",
        description = "Três dias",
        isCurrent = true,
        externalId = "manifest:ppl",
        contentVersion = 3,
        syncId = "prog-sync-777"
    )

    private fun template(id: Long, name: String, short: String, order: Int, day: String? = null) =
        WorkoutTemplateEntity(
            id = id,
            programId = 7L,
            name = name,
            shortIdentifier = short,
            orderInProgram = order,
            dayOfWeek = day,
            syncId = "tmpl-sync-$id"
        )

    @Test
    fun `buildProgramSnapshot carrega programa, treinos em ordem e exercicios de cada um`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val row = createExercise(2L, "Remada", "catalog-row")
        val squat = createExercise(3L, "Agachamento", "catalog-squat")

        // Os treinos chegam fora de ordem e com posições esparsas (5, 2, 9): a ordem relativa é o
        // que viaja, normalizada em 0..n-1.
        val templates = listOf(
            template(30L, "Legs", "C", 9, day = "Sex") to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(3L, sortOrder = 0), squat)
            ),
            template(10L, "Push", "A", 2, day = "Seg") to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 1), bench),
                TemplateExerciseWithDetails(createTemplateExercise(2L, sortOrder = 0), row)
            ),
            template(20L, "Pull", "B", 5, day = " ") to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(2L, sortOrder = 0), row)
            )
        )

        val result = builder.buildProgramSnapshot(sampleProgram, templates)

        assertTrue(result is SnapshotBuildResult.Success)
        val content = (result as SnapshotBuildResult.Success).content
        assertTrue(content is WorkoutShareContent.Program)
        val snapshot = (content as WorkoutShareContent.Program).snapshot

        assertEquals(1, snapshot.snapshotVersion)
        assertEquals("Push/Pull/Legs", snapshot.name)
        assertEquals("Três dias", snapshot.description)
        assertEquals(listOf("Push", "Pull", "Legs"), snapshot.templates.map { it.name })
        assertEquals(listOf(0, 1, 2), snapshot.templates.map { it.orderInProgram })
        assertEquals(listOf("A", "B", "C"), snapshot.templates.map { it.shortIdentifier })
        assertEquals(listOf("Seg", null, "Sex"), snapshot.templates.map { it.dayOfWeek })
        assertEquals(
            listOf("catalog-row", "catalog-bench-press"),
            snapshot.templates[0].exercises.map { it.canonicalExerciseId }
        )
        assertEquals(3, content.templateCount)
        assertEquals(4, content.exerciseCount)
    }

    @Test
    fun `buildProgramSnapshot nao transporta isCurrent, externalId, ids, syncIds, cargas nem notas`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val templates = listOf(
            template(10L, "Push", "A", 0) to listOf(
                TemplateExerciseWithDetails(
                    createTemplateExercise(1L, sortOrder = 0, plannedWeight = 120f, machineLabel = "M7", notes = "segredo"),
                    bench
                )
            )
        )

        val result = builder.buildProgramSnapshot(sampleProgram, templates) as SnapshotBuildResult.Success
        val snapshot = (result.content as WorkoutShareContent.Program).snapshot

        // O JSON que sai é a fronteira: nada do que é do dono pode estar nele, por nome ou valor.
        val json = Json.encodeToString(snapshot)
        for (forbidden in listOf("isCurrent", "externalId", "contentVersion", "syncId", "localId",
            "programId", "templateId", "plannedWeight", "machineLabel", "notes",
            "prog-sync-777", "tmpl-sync-10", "manifest:ppl", "120", "M7", "segredo")) {
            assertFalse("o snapshot não pode carregar '$forbidden': $json", json.contains(forbidden))
        }
    }

    @Test
    fun `buildProgramSnapshot bloqueia o programa inteiro quando um treino tem exercicio CUSTOM`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val custom = createExercise(9L, "Meu Exercício", "custom-9", isUserCreated = true)
        val templates = listOf(
            template(10L, "Push", "A", 0) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), bench)
            ),
            template(20L, "Pull", "B", 1) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(9L, sortOrder = 0), custom)
            )
        )

        val result = builder.buildProgramSnapshot(sampleProgram, templates)

        assertTrue(result is SnapshotBuildResult.Blocked)
        val blocked = result as SnapshotBuildResult.Blocked
        // O motivo nomeia o treino e o exercício: é o que a pessoa precisa para resolver.
        assertTrue(blocked.reasons.any { it.contains("Pull") && it.contains("Meu Exercício") })
    }

    @Test
    fun `buildProgramSnapshot bloqueia quando um treino nao tem exercicios`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val templates = listOf(
            template(10L, "Push", "A", 0) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), bench)
            ),
            template(20L, "Vazio", "B", 1) to emptyList()
        )

        val result = builder.buildProgramSnapshot(sampleProgram, templates)

        assertTrue(result is SnapshotBuildResult.Blocked)
        assertTrue((result as SnapshotBuildResult.Blocked).reasons.any { it.contains("Vazio") })
    }

    @Test
    fun `buildProgramSnapshot bloqueia um programa sem treinos`() {
        val result = builder.buildProgramSnapshot(sampleProgram, emptyList())
        assertTrue(result is SnapshotBuildResult.Blocked)
    }

    @Test
    fun `buildProgramSnapshot omite descricao em branco`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val templates = listOf(
            template(10L, "Push", "A", 0) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), bench)
            )
        )
        val result = builder.buildProgramSnapshot(sampleProgram.copy(description = "   "), templates)
        val snapshot = ((result as SnapshotBuildResult.Success).content as WorkoutShareContent.Program).snapshot
        assertNull(snapshot.description)
    }
}
