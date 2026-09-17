package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.sync.CloudSyncScope
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncMutationCoordinator
import com.example.data.sync.SyncOperation
import com.example.data.sync.SyncOutboxEntryEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O programa atual: quem o define, e o que a nuvem fica sabendo (auditoria 2026-09-12).
 *
 * Dois defeitos vizinhos moravam aqui:
 *
 * ```text
 * addProgram        comparava um Flow com null   → o primeiro programa nunca virava o atual
 * setCurrentProgram não registrava mutação       → o outro aparelho reativava o programa antigo
 * ```
 *
 * O segundo é o mais caro: `isCurrent` **viaja** no payload do programa, então o aparelho que não
 * soube da troca continua afirmando a versão dele — e o programa antigo volta a ser o atual sozinho.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutRepositoryProgramTest {

    private val ownerUid = "uid-da-conta"

    private lateinit var database: AppDatabase
    private var scope: CloudSyncScope = CloudSyncScope.Enabled("uid-da-conta")

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
            scopeProvider = { scope },
            clock = { 1_700_000_000_000L }
        )
    )

    private suspend fun programEntries(): List<SyncOutboxEntryEntity> =
        database.syncOutboxDao().pendingFor(ownerUid)
            .filter { it.entityType == SyncEntityType.WORKOUT_PROGRAM.name }

    // --------------------------------------------------------------------- primeiro programa

    @Test
    fun `o primeiro programa criado vira o atual`() = runTest {
        val repository = repository()

        repository.addProgram("Meu primeiro programa")

        val current = database.workoutDao().getCurrentProgramSync()
        assertNotNull("sem programa atual, a Home não tem o que mostrar", current)
        assertEquals("Meu primeiro programa", current!!.name)
        assertTrue(current.isCurrent)
    }

    @Test
    fun `o segundo programa criado nao rouba o atual`() = runTest {
        val repository = repository()

        repository.addProgram("Primeiro")
        repository.addProgram("Segundo")

        val current = database.workoutDao().getCurrentProgramSync()
        assertEquals("criar não é trocar: a escolha continua sendo do usuário", "Primeiro", current?.name)
    }

    @Test
    fun `criar o primeiro programa registra uma mutacao so`() = runTest {
        val repository = repository()

        repository.addProgram("Primeiro")

        // `setCurrentProgram` dentro do mesmo `mutate` não produz uma segunda entrada: mutação é
        // por agregado, e o payload é montado do Room na hora do push — já com `isCurrent`.
        val entries = programEntries()
        assertEquals(1, entries.size)
        assertEquals(SyncOperation.UPSERT.name, entries.single().operation)
        assertEquals(
            database.workoutDao().getCurrentProgramSync()!!.syncId,
            entries.single().entitySyncId
        )
    }

    // ------------------------------------------------------------------------- troca de atual

    @Test
    fun `trocar o programa atual registra os dois agregados afetados`() = runTest {
        val repository = repository()
        repository.addProgram("Programa A")
        repository.addProgram("Programa B")

        val a = database.workoutDao().getAllProgramsSync().single { it.name == "Programa A" }
        val b = database.workoutDao().getAllProgramsSync().single { it.name == "Programa B" }
        database.syncOutboxDao().acknowledge(
            ownerUid,
            database.syncOutboxDao().pendingFor(ownerUid).map { it.id }
        )

        repository.setCurrentProgram(b.id)

        // Dois: o que perdeu a marca e o que a ganhou. Registrar só um faria o outro aparelho
        // continuar afirmando o antigo e reativá-lo no ciclo seguinte.
        val entries = programEntries()
        assertEquals(2, entries.size)
        assertEquals(
            setOf(a.syncId, b.syncId),
            entries.map { it.entitySyncId }.toSet()
        )
        assertTrue(entries.all { it.operation == SyncOperation.UPSERT.name })

        assertEquals(b.id, database.workoutDao().getCurrentProgramSync()?.id)
        assertFalse(database.workoutDao().getProgramById(a.id)!!.isCurrent)
    }

    @Test
    fun `trocar para o programa que ja e o atual nao registra nada`() = runTest {
        val repository = repository()
        repository.addProgram("Programa A")
        val a = database.workoutDao().getCurrentProgramSync()!!
        database.syncOutboxDao().acknowledge(
            ownerUid,
            database.syncOutboxDao().pendingFor(ownerUid).map { it.id }
        )

        repository.setCurrentProgram(a.id)

        // Uma operação que não muda estado não registra intenção de sync nenhuma (§13.1).
        assertEquals(emptyList<String>(), programEntries().map { it.entitySyncId })
        assertTrue(database.workoutDao().getProgramById(a.id)!!.isCurrent)
    }

    @Test
    fun `existe apenas um programa atual depois da troca`() = runTest {
        val repository = repository()
        repository.addProgram("Programa A")
        repository.addProgram("Programa B")
        repository.addProgram("Programa C")

        val c = database.workoutDao().getAllProgramsSync().single { it.name == "Programa C" }
        repository.setCurrentProgram(c.id)

        val marked = database.workoutDao().getAllProgramsSync().filter { it.isCurrent }
        assertEquals("nenhuma tela sabe representar dois programas atuais", 1, marked.size)
        assertEquals(c.id, marked.single().id)
    }

    // -------------------------------------------------------------------------- sem nuvem

    @Test
    fun `sem nuvem a troca acontece e nada e registrado`() = runTest {
        scope = CloudSyncScope.Disabled
        val repository = repository()
        repository.addProgram("Programa A")
        repository.addProgram("Programa B")
        val b = database.workoutDao().getAllProgramsSync().single { it.name == "Programa B" }

        repository.setCurrentProgram(b.id)

        assertEquals(b.id, database.workoutDao().getCurrentProgramSync()?.id)
        assertEquals(0, database.syncOutboxDao().count())
        assertNull(database.syncOutboxDao().pendingFor(ownerUid).firstOrNull())
    }

    // ------------------------------------------------------ programa importado inteiro (T19.3)

    @Test
    fun `addProgramWithTemplates cria tudo numa transacao, nunca como atual, e registra o programa antes dos treinos`() = runTest {
        val repository = repository()
        val exerciseId = database.workoutDao().insertExercise(
            com.example.data.local.ExerciseEntity(name = "Supino", canonicalId = "cat-bench")
        )
        // Nenhum programa existe: mesmo assim o importado **não** vira o atual (T19.3 §isCurrent).
        assertNull(database.workoutDao().getCurrentProgramSync())

        val program = com.example.data.local.WorkoutProgramEntity(name = "PPL", isCurrent = true)
        val push = com.example.data.local.WorkoutTemplateEntity(programId = 0, name = "Push", shortIdentifier = "A", orderInProgram = 0)
        val pull = com.example.data.local.WorkoutTemplateEntity(programId = 0, name = "Pull", shortIdentifier = "B", orderInProgram = 1)
        var seenProgramId: Long? = null

        val programId = repository.addProgramWithTemplates(
            program = program,
            templates = listOf(
                NewTemplate(
                    push,
                    listOf(com.example.data.local.WorkoutTemplateExerciseEntity(templateId = 0, exerciseId = exerciseId, sortOrder = 0)),
                    // Dois dias no mesmo treino (T19.8): a cópia nasce com a agenda inteira.
                    listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY)
                ),
                NewTemplate(
                    pull,
                    listOf(com.example.data.local.WorkoutTemplateExerciseEntity(templateId = 0, exerciseId = exerciseId, sortOrder = 0))
                )
            )
        ) { id -> seenProgramId = id }

        assertEquals(programId, seenProgramId)
        // `isCurrent = true` na entidade de entrada é ignorado: quem decide é o usuário, depois.
        assertFalse(database.workoutDao().getProgramById(programId)!!.isCurrent)
        assertNull(database.workoutDao().getCurrentProgramSync())
        val templates = database.workoutDao().getTemplatesForProgramSync(programId)
        assertEquals(listOf("Push", "Pull"), templates.map { it.name })
        assertEquals(listOf(programId, programId), templates.map { it.programId })
        val withSchedule = database.workoutDao().getTemplatesWithScheduleForProgramSync(programId)
        assertEquals(
            listOf(listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY), emptyList()),
            withSchedule.map { it.scheduledDays }
        )

        // A Outbox: o programa primeiro, depois cada treino — a ordem em que o outro aparelho
        // consegue aplicar (o treino referencia o programa por syncId).
        val entries = database.syncOutboxDao().pendingFor(ownerUid)
        assertEquals(
            listOf(
                SyncEntityType.WORKOUT_PROGRAM.name to program.syncId,
                SyncEntityType.WORKOUT_TEMPLATE.name to push.syncId,
                SyncEntityType.WORKOUT_TEMPLATE.name to pull.syncId
            ),
            entries.map { it.entityType to it.entitySyncId }
        )
        assertTrue(entries.all { it.operation == SyncOperation.UPSERT.name })
    }

    @Test
    fun `addProgramWithTemplates que falha no final nao deixa programa nem treino`() = runTest {
        val repository = repository()
        val exerciseId = database.workoutDao().insertExercise(
            com.example.data.local.ExerciseEntity(name = "Supino", canonicalId = "cat-bench")
        )

        val failed = runCatching {
            repository.addProgramWithTemplates(
                program = com.example.data.local.WorkoutProgramEntity(name = "PPL"),
                templates = listOf(
                    NewTemplate(
                        com.example.data.local.WorkoutTemplateEntity(programId = 0, name = "Push", orderInProgram = 0),
                        listOf(com.example.data.local.WorkoutTemplateExerciseEntity(templateId = 0, exerciseId = exerciseId)),
                        listOf(java.time.DayOfWeek.MONDAY)
                    )
                )
            ) { error("recibo indisponível") }
        }

        assertTrue(failed.isFailure)
        assertEquals(0, database.workoutDao().countPrograms())
        assertEquals("a agenda também é desfeita", 0, database.workoutDao().countSchedules())
        assertTrue(database.syncOutboxDao().pendingFor(ownerUid).isEmpty())
    }
}
