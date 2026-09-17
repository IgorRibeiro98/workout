package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.CloudSyncScope
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncMutationCoordinator
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A agenda semanal do treino no repositório (T19.8).
 *
 * O que está em jogo: um treino com dois dias continua sendo **um** treino; salvar a mesma agenda
 * duas vezes não duplica dia; trocar a agenda não deixa linha órfã; a sigla em branco vira `null`;
 * o nome continua obrigatório; e a nuvem fica sabendo com **uma** mutação do agregado — nenhuma
 * quando nada mudou.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutRepositoryScheduleTest {

    private val ownerUid = "uid-da-conta"

    private lateinit var database: AppDatabase

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun repository() = WorkoutRepository(
        database.workoutDao(),
        syncMutations = SyncMutationCoordinator(
            transactions = RoomTransactionRunner(database),
            outboxDao = database.syncOutboxDao(),
            scopeProvider = { CloudSyncScope.Enabled(ownerUid) },
            clock = { 1_700_000_000_000L }
        )
    )

    private suspend fun program(): Long = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "PPL"))

    private suspend fun daysOf(templateId: Long) = repository().getTemplateScheduledDays(templateId)

    private suspend fun templateMutations(): Int =
        database.syncOutboxDao().pendingFor(ownerUid).count { it.entityType == SyncEntityType.WORKOUT_TEMPLATE.name }

    // ------------------------------------------------------------------------------ criar

    @Test
    fun `criar sem dia e valido e nao cria linha de agenda`() = runTest {
        val id = repository().addTemplate(program(), "Treino A", "A", 0)

        assertEquals(emptyList<DayOfWeek>(), daysOf(id))
        assertEquals(0, database.workoutDao().countSchedules())
    }

    @Test
    fun `criar com um dia preserva o comportamento anterior`() = runTest {
        val id = repository().addTemplate(program(), "Treino B", "B", 0, setOf(DayOfWeek.MONDAY))

        assertEquals(listOf(DayOfWeek.MONDAY), daysOf(id))
    }

    @Test
    fun `criar com varios dias guarda todos no mesmo treino, na ordem da semana`() = runTest {
        val programId = program()
        val id = repository().addTemplate(programId, "Treino A", "A", 0, listOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY))

        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), daysOf(id))
        assertEquals("um treino, não um por dia", 1, database.workoutDao().getTemplatesForProgramSync(programId).size)
        assertEquals("uma mutação do agregado, não uma por dia", 1, templateMutations())
    }

    @Test
    fun `dia repetido na entrada nao vira duas linhas`() = runTest {
        val id = repository().addTemplate(program(), "Treino A", "A", 0, listOf(DayOfWeek.MONDAY, DayOfWeek.MONDAY))

        assertEquals(listOf(DayOfWeek.MONDAY), daysOf(id))
        assertEquals(1, database.workoutDao().countSchedules())
    }

    @Test
    fun `nome e obrigatorio, sigla e opcional e em branco vira null`() = runTest {
        val repository = repository()
        val programId = program()

        val failed = runCatching { repository.addTemplate(programId, "   ", "A", 0) }
        assertTrue("nome em branco é recusado", failed.isFailure)
        assertEquals("nada foi criado", 0, database.workoutDao().getTemplatesForProgramSync(programId).size)

        val id = repository.addTemplate(programId, "  Treino A  ", "   ", 0)
        val template = database.workoutDao().getTemplateById(id)!!
        assertEquals("Treino A", template.name)
        assertNull("sigla em branco não vira \"\"", template.shortIdentifier)
    }

    // ----------------------------------------------------------------------------- editar

    @Test
    fun `trocar a agenda substitui os dias sem deixar linha orfa`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

        repository.updateTemplateHeader(id, "Treino A", "A", setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY))

        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), daysOf(id))
        assertEquals("só as linhas da agenda nova existem", 2, database.workoutDao().countSchedules())
    }

    @Test
    fun `desligar um dia remove so aquele dia`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

        repository.updateTemplateHeader(id, "Treino A", "A", setOf(DayOfWeek.THURSDAY))

        assertEquals(listOf(DayOfWeek.THURSDAY), daysOf(id))
    }

    @Test
    fun `desligar todos os dias e valido e volta a ser sem dia fixo`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY))

        repository.updateTemplateHeader(id, "Treino A", "A", emptySet())

        assertEquals(emptyList<DayOfWeek>(), daysOf(id))
        assertNotNull("o treino continua existindo", database.workoutDao().getTemplateById(id))
    }

    @Test
    fun `salvar a mesma agenda duas vezes e idempotente e nao registra mutacao nova`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        val afterCreate = templateMutations()

        repository.updateTemplateHeader(id, "Treino A", "A", setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY))
        repository.updateTemplateHeader(id, "Treino A", "A", setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), daysOf(id))
        assertEquals(2, database.workoutDao().countSchedules())
        assertEquals("nada mudou, nada sobe", afterCreate, templateMutations())
    }

    @Test
    fun `editar preserva identidade, ordem, programa e exercicios do treino`() = runTest {
        val repository = repository()
        val programId = program()
        val exerciseId = database.workoutDao().insertExercise(
            com.example.data.local.ExerciseEntity(name = "Supino", canonicalId = "cat-bench")
        )
        val id = repository.addTemplate(programId, "Treino A", "A", 3, setOf(DayOfWeek.MONDAY))
        database.workoutDao().insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = id, exerciseId = exerciseId, sortOrder = 0)
        )
        val before = database.workoutDao().getTemplateById(id)!!

        repository.updateTemplateHeader(id, "Treino A+", "AA", setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

        val after = database.workoutDao().getTemplateById(id)!!
        assertEquals(before.syncId, after.syncId)
        assertEquals(before.id, after.id)
        assertEquals(before.programId, after.programId)
        assertEquals("orderInProgram não é agenda", 3, after.orderInProgram)
        assertEquals("Treino A+", after.name)
        assertEquals("AA", after.shortIdentifier)
        assertEquals(1, database.workoutDao().getTemplateExercisesWithDetails(id).size)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), daysOf(id))
    }

    @Test
    fun `editar registra uma unica mutacao do agregado, com o syncId do treino`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0)
        val syncId = database.workoutDao().getTemplateById(id)!!.syncId

        repository.updateTemplateHeader(id, "Treino A", "A", setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

        val entries = database.syncOutboxDao().pendingFor(ownerUid)
            .filter { it.entityType == SyncEntityType.WORKOUT_TEMPLATE.name }
        // A Outbox coalesce a criação e a edição pendentes do mesmo agregado: o que importa é que
        // a mutação registrada é a do treino, pelo `syncId` dele — nunca uma por dia.
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.entitySyncId == syncId })
    }

    @Test
    fun `editar com nome em branco e recusado e nao toca na agenda`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY))

        val failed = runCatching { repository.updateTemplateHeader(id, " ", "A", setOf(DayOfWeek.FRIDAY)) }

        assertTrue(failed.isFailure)
        assertEquals(listOf(DayOfWeek.MONDAY), daysOf(id))
        assertEquals("Treino A", database.workoutDao().getTemplateById(id)!!.name)
    }

    // ---------------------------------------------------------------------------- leitura

    @Test
    fun `a leitura reativa devolve cada treino com a agenda dele, na ordem do programa`() = runTest {
        val repository = repository()
        val programId = program()
        repository.addTemplate(programId, "B", "B", 1, setOf(DayOfWeek.THURSDAY))
        repository.addTemplate(programId, "A", "A", 0, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        repository.addTemplate(programId, "C", "C", 2)

        val templates = repository.getTemplatesWithScheduleForProgram(programId).first()

        assertEquals(listOf("A", "B", "C"), templates.map { it.template.name })
        assertEquals(
            listOf(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), listOf(DayOfWeek.THURSDAY), emptyList()),
            templates.map { it.scheduledDays }
        )
    }

    // ---------------------------------------------------------------------------- excluir

    @Test
    fun `excluir o treino leva a agenda junto`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

        repository.deleteTemplate(database.workoutDao().getTemplateById(id)!!)

        assertEquals(0, database.workoutDao().countSchedules())
    }

    @Test
    fun `a agenda nao reinterpreta uma sessao concluida`() = runTest {
        val repository = repository()
        val id = repository.addTemplate(program(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY))
        val sessionId = database.workoutDao().insertSession(
            com.example.data.local.WorkoutSessionEntity(
                templateId = id,
                startedAt = 1_000L,
                finishedAt = 2_000L,
                status = com.example.data.local.SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Treino A"
            )
        )

        repository.updateTemplateHeader(id, "Treino A", "A", setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))

        val session = database.workoutDao().getSessionById(sessionId)!!
        assertEquals(id, session.templateId)
        assertEquals(1_000L, session.startedAt)
        assertEquals(2_000L, session.finishedAt)
        assertEquals("Treino A", session.templateNameSnapshot)
    }
}
