package com.example.data.sync

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A garantia central da T16.3: **alteração de domínio e registro na Outbox são atômicos**.
 *
 * Não basta escrever os dois. Se o app morrer entre eles, ou se um falhar, o resultado precisa ser
 * "nenhum dos dois" — senão o Spark fica com um treino que o servidor nunca saberá que mudou, ou
 * com uma mutação apontando para algo que não aconteceu.
 *
 * Os testes usam Room de verdade e os repositórios de verdade. A UI não aparece em lugar nenhum
 * aqui — e é assim que tem de ser: ela não conhece Outbox.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncOutboxTransactionTest {

    private lateinit var database: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val ownerUid = "uid-do-usuario"
    private var scope: CloudSyncScope = CloudSyncScope.Enabled("uid-do-usuario")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun coordinator(idGenerator: IdGenerator = RandomUuidIdGenerator) =
        SyncMutationCoordinator(
            transactions = RoomTransactionRunner(database),
            outboxDao = database.syncOutboxDao(),
            scopeProvider = { scope },
            idGenerator = idGenerator,
            clock = { 1_700_000_000_000L }
        )

    private fun repository(idGenerator: IdGenerator = RandomUuidIdGenerator) =
        WorkoutRepository(database.workoutDao(), syncMutations = coordinator(idGenerator))

    @Test
    fun domainWriteAndOutboxEntryCommitTogether() = runTest {
        val repository = repository()
        repository.addTemplate(programId = programId(), name = "Treino A", shortId = "A", order = 0)

        val template = database.workoutDao().getAllTemplatesSync().single { it.name == "Treino A" }
        val entries = database.syncOutboxDao().pendingFor(ownerUid)
            .filter { it.entityType == SyncEntityType.WORKOUT_TEMPLATE.name }

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(template.syncId, entry.entitySyncId)
        assertEquals(SyncOperation.UPSERT.name, entry.operation)
        assertEquals(SyncOutboxStatus.PENDING.name, entry.status)
        assertEquals(ownerUid, entry.ownerUid)
        assertEquals(1_700_000_000_000L, entry.createdAt)
        assertEquals(0, entry.attemptCount)

        // `clientMutationId` identifica a alteração; `entitySyncId`, a entidade. Nunca o mesmo valor.
        assertNotEquals(entry.entitySyncId, entry.clientMutationId)
        assertTrue(entry.clientMutationId.isNotBlank())
    }

    @Test
    fun outboxFailureRollsBackTheDomainWrite() = runTest {
        // Um gerador que devolve sempre o mesmo `clientMutationId` transforma a segunda mutação em
        // violação da restrição UNIQUE — a falha "dentro da transação, depois da escrita de
        // domínio" que precisamos provocar.
        val repository = repository(idGenerator = { "mutation-fixa" })
        val programId = programId()

        repository.addTemplate(programId = programId, name = "Primeiro", shortId = "A", order = 0)
        assertEquals(1, database.syncOutboxDao().count())

        val failed = runCatching {
            repository.addTemplate(programId = programId, name = "Segundo", shortId = "B", order = 1)
        }.isFailure
        assertTrue("a inserção duplicada na Outbox deveria falhar", failed)

        // Nem o treino, nem a entrada.
        assertEquals(
            emptyList<String>(),
            database.workoutDao().getAllTemplatesSync().map { it.name }.filter { it == "Segundo" }
        )
        assertEquals(1, database.syncOutboxDao().count())
    }

    @Test
    fun domainFailureRollsBackTheOutboxEntry() = runTest {
        val coordinator = coordinator()
        val dao = database.workoutDao()
        val programId = programId()
        val template = com.example.data.local.WorkoutTemplateEntity(
            programId = programId,
            name = "Vai falhar",
            orderInProgram = 0
        )

        val failed = runCatching {
            coordinator.mutate {
                dao.insertTemplate(template)
                upsert(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
                // A regra de domínio recusa a alteração depois da escrita.
                error("regra de domínio rejeitou a alteração")
            }
        }.isFailure

        assertTrue(failed)
        assertEquals(0, dao.getAllTemplatesSync().count { it.name == "Vai falhar" })
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun rejectedDomainRuleRecordsNothing() = runTest {
        val repository = repository()
        val canonical = com.example.data.local.ExerciseEntity(
            name = "Supino Reto",
            canonicalId = "canonical.supino",
            isUserCreated = false
        )
        val id = database.workoutDao().insertExercise(canonical)

        // Apagar exercício canônico não é permitido. Uma operação que não acontece não registra
        // intenção de sync.
        repository.deleteExercise(canonical.copy(id = id))

        assertNotNull(database.workoutDao().getExerciseById(id))
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun repeatedChangesToTheSameAggregateCoalesceIntoOneMutation() = runTest {
        val repository = repository()
        val programId = programId()
        val templateId = repository.addTemplate(programId, "Treino A", "A", 0)
        val exerciseId = database.workoutDao().insertExercise(
            com.example.data.local.ExerciseEntity(name = "Supino", canonicalId = "canonical.supino")
        )

        val outbox = database.syncOutboxDao()
        val afterCreate = outbox.count()

        // Três edições do mesmo treino antes de qualquer envio.
        repository.addExerciseToTemplate(templateId, exerciseId, sortOrder = 0)
        repository.updateTemplateExercises(
            database.workoutDao().getTemplateExercisesWithDetails(templateId)
                .map { it.templateExercise.copy(sortOrder = 5) }
        )
        repository.updateTemplateExerciseFull(
            database.workoutDao().getTemplateExercisesWithDetails(templateId)
                .first().templateExercise.copy(targetSets = 5)
        )

        // Continuam sendo uma única mutação pendente daquele treino: o conteúdo é montado a partir
        // do Room no envio, então as três resolveriam para exatamente o mesmo payload.
        assertEquals(afterCreate, outbox.count())
        val template = database.workoutDao().getTemplateById(templateId)!!
        val entries = outbox.pendingFor(ownerUid)
            .filter { it.entitySyncId == template.syncId }
        assertEquals(1, entries.size)
        assertEquals(SyncOperation.UPSERT.name, entries.single().operation)
    }

    @Test
    fun deleteIsNotSwallowedByAPendingUpsert() = runTest {
        val repository = repository()
        val programId = programId()
        val templateId = repository.addTemplate(programId, "Treino A", "A", 0)
        val template = database.workoutDao().getTemplateById(templateId)!!

        repository.deleteTemplate(template)

        val entries = database.syncOutboxDao().pendingFor(ownerUid)
            .filter { it.entitySyncId == template.syncId }
        assertEquals(
            listOf(SyncOperation.UPSERT.name, SyncOperation.DELETE.name),
            entries.map { it.operation }
        )
        // Duas alterações distintas do mesmo agregado: dois `clientMutationId`.
        assertEquals(2, entries.map { it.clientMutationId }.distinct().size)
    }

    @Test
    fun batchOfChildRowsProducesOneAggregateMutation() = runTest {
        val repository = repository()
        val programId = programId()
        // O treino entra pelo DAO de propósito: assim a Outbox começa vazia e o único registro
        // possível é o da reordenação.
        val templateId = database.workoutDao().insertTemplate(
            com.example.data.local.WorkoutTemplateEntity(programId = programId, name = "Treino A", orderInProgram = 0)
        )
        val exerciseIds = (1..4).map {
            database.workoutDao().insertExercise(
                com.example.data.local.ExerciseEntity(name = "Exercício $it", canonicalId = "canonical.$it")
            )
        }
        exerciseIds.forEachIndexed { index, exerciseId ->
            database.workoutDao().insertTemplateExercise(
                WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = index)
            )
        }
        val template = database.workoutDao().getTemplateById(templateId)!!
        val outbox = database.syncOutboxDao()
        assertEquals(0, outbox.count())

        // Reordenar: quatro linhas alteradas, um agregado alterado.
        val reordered = database.workoutDao().getTemplateExercisesWithDetails(templateId)
            .reversed()
            .mapIndexed { index, item -> item.templateExercise.copy(sortOrder = index) }
        repository.updateTemplateExercises(reordered)

        assertEquals(1, outbox.count())
        assertEquals(
            1,
            outbox.pendingFor(ownerUid).count {
                it.entitySyncId == template.syncId && it.operation == SyncOperation.UPSERT.name
            }
        )
    }

    @Test
    fun cloudDisabledProducesNoEntriesAndKeepsLocalWritesWorking() = runTest {
        scope = CloudSyncScope.Disabled
        val repository = repository()
        val measurements = BodyMeasurementRepository(
            dao = database.bodyMeasurementDao(),
            syncMutations = coordinator()
        )

        val templateId = repository.addTemplate(programId(), "Treino A", "A", 0)
        measurements.insertMeasurement(BodyMeasurementEntity(date = 10, weightKg = 80f))

        // O Spark continua completo sem nuvem.
        assertNotNull(database.workoutDao().getTemplateById(templateId))
        assertEquals(1, database.bodyMeasurementDao().getAllMeasurementsSync().size)
        // E nada é registrado para envio.
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun bodyMeasurementDeleteRecordsTheIdentityItHadBeforeVanishing() = runTest {
        val measurements = BodyMeasurementRepository(
            dao = database.bodyMeasurementDao(),
            syncMutations = coordinator()
        )
        val measurement = BodyMeasurementEntity(date = 10, weightKg = 80f)
        val id = measurements.insertMeasurement(measurement)

        measurements.deleteMeasurementById(id)

        val entries = database.syncOutboxDao().pendingFor(ownerUid)
            .filter { it.entityType == SyncEntityType.BODY_MEASUREMENT.name }
        assertEquals(
            listOf(SyncOperation.UPSERT.name, SyncOperation.DELETE.name),
            entries.map { it.operation }
        )
        assertEquals(setOf(measurement.syncId), entries.map { it.entitySyncId }.toSet())
    }

    private suspend fun programId(): Long =
        database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
}
