package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateScheduleEntity
import com.example.data.local.WorkoutTemplateWithSchedule
import java.time.DayOfWeek
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.social.WorkoutShareSnapshotLimits
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

    private fun SnapshotBuildResult.Success.workout() =
        (content as WorkoutShareContent.Workout).snapshot

    private fun SnapshotBuildResult.Success.program() =
        (content as WorkoutShareContent.Program).snapshot

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
    fun `treino sem exercicios vira oferta V2, e nao bloqueio (T19_H2)`() {
        val result = builder.buildSnapshot(sampleTemplate, emptyList())

        // Um treino vazio é um estado legítimo do Workout local. Até a T19.H2 o Social o recusava,
        // decidindo pelo usuário o que é um treino válido.
        val snapshot = (result as SnapshotBuildResult.Success).workout()
        assertEquals(WorkoutShareSnapshotLimits.VERSION_V2, snapshot.snapshotVersion)
        assertTrue(snapshot.exercises.isEmpty())
        assertTrue(snapshot.customExercises.isEmpty())
    }

    @Test
    fun `exercicio criado pelo usuario viaja como copia, nunca como referencia (T19_H2)`() {
        val ex1 = createExercise(1L, "Meu Supino Especial", "custom-id-123", isUserCreated = true)
        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), ex1)
        )

        val snapshot = (builder.buildSnapshot(sampleTemplate, details) as SnapshotBuildResult.Success).workout()

        assertEquals(WorkoutShareSnapshotLimits.VERSION_V2, snapshot.snapshotVersion)
        val custom = snapshot.customExercises.single()
        assertEquals("custom-1", custom.ref)
        assertEquals("Meu Supino Especial", custom.name)
        // A posição aponta para a chave, e **não** carrega id de catálogo.
        val position = snapshot.exercises.single()
        assertEquals("custom-1", position.customExerciseRef)
        assertNull(position.canonicalExerciseId)
        // Nada do remetente atravessa: nem o `localId`, nem o `canonicalId` que a linha tinha.
        val json = Json.encodeToString(snapshot)
        assertFalse(json.contains("custom-id-123"))
        assertFalse(json.contains("\"localId\""))
        assertFalse(json.contains("\"syncId\""))
    }

    @Test
    fun `o mesmo CUSTOM em duas posicoes do treino recebe uma chave so`() {
        val custom = createExercise(9L, "Meu Supino", null, isUserCreated = true)
        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(9L, sortOrder = 0), custom),
            TemplateExerciseWithDetails(createTemplateExercise(9L, sortOrder = 1), custom)
        )

        val snapshot = (builder.buildSnapshot(sampleTemplate, details) as SnapshotBuildResult.Success).workout()

        assertEquals(1, snapshot.customExercises.size)
        assertEquals(listOf("custom-1", "custom-1"), snapshot.exercises.map { it.customExerciseRef })
    }

    @Test
    fun `um treino so de exercicios do catalogo continua sendo escrito como V1`() {
        val ex1 = createExercise(1L, "Supino", "catalog-bench-press")
        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), ex1)
        )

        val snapshot = (builder.buildSnapshot(sampleTemplate, details) as SnapshotBuildResult.Success).workout()

        // O app escreve a versão **mínima** que representa a oferta: uma V1 é aceita por qualquer
        // Spark Backend já publicado, e o caminho que já funcionava não passa a depender do novo.
        assertEquals(WorkoutShareSnapshotLimits.VERSION_V1, snapshot.snapshotVersion)
        assertTrue(snapshot.customExercises.isEmpty())
    }

    @Test
    fun `sigla maior que o limite do servidor bloqueia antes da requisicao (H2_6)`() {
        val ex1 = createExercise(1L, "Supino", "catalog-bench-press")
        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), ex1)
        )

        val result = builder.buildSnapshot(sampleTemplate.copy(shortIdentifier = "Superiores A"), details)

        val blocked = result as SnapshotBuildResult.Blocked
        assertTrue(blocked.reasons.any { it.contains("sigla") })
    }

    @Test
    fun `sortOrder viaja normalizado, e um buraco na ordem nao vira recusa do servidor`() {
        val a = createExercise(1L, "Supino", "catalog-bench-press")
        val b = createExercise(2L, "Remada", "catalog-row")
        val details = listOf(
            TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 7), a),
            TemplateExerciseWithDetails(createTemplateExercise(2L, sortOrder = 41), b)
        )

        val snapshot = (builder.buildSnapshot(sampleTemplate, details) as SnapshotBuildResult.Success).workout()

        // 41 está fora do 0..30 que o servidor aceita; o que a oferta transporta é a ordem.
        assertEquals(listOf(0, 1), snapshot.exercises.map { it.sortOrder })
        assertEquals(listOf("catalog-bench-press", "catalog-row"), snapshot.exercises.map { it.canonicalExerciseId })
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

    // ------------------------------------------------------------------ faixas (H1.2)

    @Test
    fun `buildSnapshot blocks when restDurationSeconds exceeds 600`() {
        val ex1 = createExercise(1L, "Agachamento", "catalog-squat")
        val details = listOf(
            TemplateExerciseWithDetails(
                createTemplateExercise(1L, sortOrder = 0, restDurationSeconds = 3600),
                ex1
            )
        )

        val result = builder.buildSnapshot(sampleTemplate, details)
        assertTrue("Descanso acima de 600s deve ser bloqueado", result is SnapshotBuildResult.Blocked)
        assertTrue((result as SnapshotBuildResult.Blocked).reasons.any { it.contains("Agachamento") })
    }

    @Test
    fun `buildSnapshot blocks when targetSets exceeds 20`() {
        val ex1 = createExercise(1L, "Supino", "catalog-bench-press")
        val details = listOf(
            TemplateExerciseWithDetails(
                createTemplateExercise(1L, sortOrder = 0, targetSets = 50),
                ex1
            )
        )

        val result = builder.buildSnapshot(sampleTemplate, details)
        assertTrue("targetSets acima de 20 deve ser bloqueado", result is SnapshotBuildResult.Blocked)
    }

    @Test
    fun `buildSnapshot blocks when minReps is greater than maxReps`() {
        val ex1 = createExercise(1L, "Remada", "catalog-row")
        val details = listOf(
            TemplateExerciseWithDetails(
                createTemplateExercise(1L, sortOrder = 0, minReps = 20, maxReps = 10),
                ex1
            )
        )

        val result = builder.buildSnapshot(sampleTemplate, details)
        assertTrue("minReps > maxReps deve ser bloqueado", result is SnapshotBuildResult.Blocked)
    }

    @Test
    fun `buildSnapshot accepts the exact bounds 1-20 sets, 1-100 reps and 0-600s rest`() {
        val ex1 = createExercise(1L, "Supino", "catalog-bench-press")
        val details = listOf(
            TemplateExerciseWithDetails(
                createTemplateExercise(
                    1L,
                    sortOrder = 0,
                    targetSets = 20,
                    minReps = 1,
                    maxReps = 100,
                    restDurationSeconds = 600
                ),
                ex1
            )
        )

        val result = builder.buildSnapshot(sampleTemplate, details)
        assertTrue("Os limites inclusivos não deveriam ser bloqueados", result is SnapshotBuildResult.Success)
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

    private fun template(id: Long, name: String, short: String, order: Int, days: List<DayOfWeek> = emptyList()) =
        WorkoutTemplateWithSchedule(
            template = WorkoutTemplateEntity(
                id = id,
                programId = 7L,
                name = name,
                shortIdentifier = short,
                orderInProgram = order,
                syncId = "tmpl-sync-$id"
            ),
            // Fora de ordem de propósito: o snapshot normaliza para a ordem da semana.
            schedules = days.reversed().map { WorkoutTemplateScheduleEntity(id, it.name) }
        )

    @Test
    fun `buildProgramSnapshot carrega programa, treinos em ordem e exercicios de cada um`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val row = createExercise(2L, "Remada", "catalog-row")
        val squat = createExercise(3L, "Agachamento", "catalog-squat")

        // Os treinos chegam fora de ordem e com posições esparsas (5, 2, 9): a ordem relativa é o
        // que viaja, normalizada em 0..n-1.
        val templates = listOf(
            template(30L, "Legs", "C", 9, days = listOf(DayOfWeek.FRIDAY)) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(3L, sortOrder = 0), squat)
            ),
            template(10L, "Push", "A", 2, days = listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 1), bench),
                TemplateExerciseWithDetails(createTemplateExercise(2L, sortOrder = 0), row)
            ),
            template(20L, "Pull", "B", 5) to listOf(
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
        // Os dias viajam canônicos, na ordem da semana, e um treino pode ter mais de um (T19.8).
        assertEquals(
            listOf(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), emptyList(), listOf(DayOfWeek.FRIDAY)),
            snapshot.templates.map { it.scheduledDays }
        )
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
    fun `o mesmo CUSTOM em dois treinos do programa aparece uma vez, referenciado duas (T19_H2)`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val custom = createExercise(9L, "Meu Exercício", null, isUserCreated = true)
        val templates = listOf(
            template(10L, "Push", "A", 0) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), bench),
                TemplateExerciseWithDetails(createTemplateExercise(9L, sortOrder = 1), custom)
            ),
            template(20L, "Pull", "B", 1) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(9L, sortOrder = 0), custom)
            )
        )

        val snapshot = (builder.buildProgramSnapshot(sampleProgram, templates) as SnapshotBuildResult.Success).program()

        assertEquals(WorkoutShareSnapshotLimits.VERSION_V2, snapshot.snapshotVersion)
        // Uma entrada só para a oferta inteira: é o que faz o destinatário criar **um** exercício.
        assertEquals(listOf("Meu Exercício"), snapshot.customExercises.map { it.name })
        val ref = snapshot.customExercises.single().ref
        assertEquals(ref, snapshot.templates[0].exercises.last().customExerciseRef)
        assertEquals(ref, snapshot.templates[1].exercises.single().customExerciseRef)
    }

    @Test
    fun `buildProgramSnapshot bloqueia o programa inteiro quando um treino tem exercicio fora da faixa`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val outOfRange = createExercise(9L, "Descanso Longo", "catalog-long-rest")
        val templates = listOf(
            template(10L, "Push", "A", 0) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), bench)
            ),
            template(20L, "Pull", "B", 1) to listOf(
                TemplateExerciseWithDetails(
                    createTemplateExercise(9L, sortOrder = 0, restDurationSeconds = 900),
                    outOfRange
                )
            )
        )

        val result = builder.buildProgramSnapshot(sampleProgram, templates)

        assertTrue(result is SnapshotBuildResult.Blocked)
        val blocked = result as SnapshotBuildResult.Blocked
        assertTrue(blocked.reasons.any { it.contains("Pull") && it.contains("Descanso Longo") })
    }

    @Test
    fun `um programa com um treino vazio continua compartilhavel (T19_H2)`() {
        val bench = createExercise(1L, "Supino", "catalog-bench-press")
        val templates = listOf(
            template(10L, "Push", "A", 0) to listOf(
                TemplateExerciseWithDetails(createTemplateExercise(1L, sortOrder = 0), bench)
            ),
            template(20L, "Vazio", "B", 1) to emptyList()
        )

        val snapshot = (builder.buildProgramSnapshot(sampleProgram, templates) as SnapshotBuildResult.Success).program()

        assertEquals(WorkoutShareSnapshotLimits.VERSION_V2, snapshot.snapshotVersion)
        assertEquals(2, snapshot.templates.size)
        assertTrue(snapshot.templates.single { it.name == "Vazio" }.exercises.isEmpty())
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
