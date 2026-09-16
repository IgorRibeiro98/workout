package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutShareImportReceiptEntity
import com.example.data.local.WorkoutShareReceiptDao
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncMutationCoordinator
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedProgramSnapshot
import com.example.domain.social.SharedProgramTemplateSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareContent
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareError
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareKind
import com.example.domain.social.WorkoutShareOtherUser
import com.example.domain.social.WorkoutShareOutcome
import com.example.domain.social.WorkoutShareStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutShareImporterTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var fakeGateway: FakeWorkoutShareGateway
    private lateinit var importer: WorkoutShareImporter

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * O servidor de mentira: `acceptShare` devolve o que estiver em [acceptResult] e conta as
     * chamadas — é o que permite afirmar que o aceite acontece **antes** da escrita local, e que
     * um retry passa por ele de novo.
     */
    private class FakeWorkoutShareGateway : WorkoutShareGateway {
        override val isConfigured: Boolean = true
        val completedImports = mutableListOf<String>()
        val acceptedShares = mutableListOf<String>()
        var acceptResult: WorkoutShareOutcome<WorkoutShareDetail> =
            WorkoutShareOutcome.Failure(WorkoutShareError.SHARE_NOT_FOUND)

        override suspend fun createShare(
            recipientSocialId: String,
            clientRequestId: String,
            content: WorkoutShareContent
        ): WorkoutShareOutcome<WorkoutShareDetail> = throw UnsupportedOperationException()

        override suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(emptyList())

        override suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(emptyList())

        override suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
            throw UnsupportedOperationException()

        override suspend fun acceptShare(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> {
            acceptedShares.add(shareId)
            return acceptResult
        }

        override suspend fun completeImport(shareId: String): WorkoutShareOutcome<Unit> {
            completedImports.add(shareId)
            return WorkoutShareOutcome.Success(Unit)
        }

        override suspend fun declineShare(shareId: String): WorkoutShareOutcome<Unit> =
            WorkoutShareOutcome.Success(Unit)

        override suspend fun cancelShare(shareId: String): WorkoutShareOutcome<Unit> =
            WorkoutShareOutcome.Success(Unit)
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WorkoutRepository(database.workoutDao())
        fakeGateway = FakeWorkoutShareGateway()
        importer = WorkoutShareImporter(
            workoutRepository = repository,
            receiptDao = database.workoutShareReceiptDao(),
            gateway = fakeGateway,
            clock = { 1700000000000L }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedCatalogExercise(name: String, canonicalId: String): Long {
        return database.workoutDao().insertExercise(
            ExerciseEntity(
                name = name,
                canonicalId = canonicalId,
                isUserCreated = false
            )
        )
    }

    private suspend fun seedProgram(name: String = "Meu Programa"): Long {
        return database.workoutDao().insertProgram(
            WorkoutProgramEntity(
                name = name,
                isCurrent = true
            )
        )
    }

    private fun detail(shareId: String, content: WorkoutShareContent?, status: WorkoutShareStatus = WorkoutShareStatus.ACCEPTED) =
        WorkoutShareDetail(
            shareId = shareId,
            kind = content?.kind ?: WorkoutShareKind.WORKOUT_TEMPLATE,
            status = status,
            createdAt = 1L,
            expiresAt = 2L,
            sender = WorkoutShareOtherUser("friend-1", "Carlos"),
            recipient = WorkoutShareOtherUser("me", "Eu"),
            content = content
        )

    @Test
    fun `importShare successfully imports independent template with stripped private fields and records receipt`() = runTest {
        val ex1Id = seedCatalogExercise("Supino Reto", "cat-bench")
        val ex2Id = seedCatalogExercise("Tríceps Corda", "cat-triceps")
        val programId = seedProgram()

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = "Treino A Compartilhado",
            shortIdentifier = "A",
            exercises = listOf(
                SharedExerciseSnapshot(
                    canonicalExerciseId = "cat-bench",
                    sortOrder = 0,
                    targetSets = 4,
                    minReps = 8,
                    maxReps = 10,
                    restDurationSeconds = 90
                ),
                SharedExerciseSnapshot(
                    canonicalExerciseId = "cat-triceps",
                    sortOrder = 1,
                    targetSets = 3,
                    minReps = 12,
                    maxReps = 15,
                    restDurationSeconds = 60
                )
            )
        )

        val result = importer.importShare("share-uuid-1", snapshot, programId)

        assertTrue(result is WorkoutShareImportResult.Success)
        val templateId = (result as WorkoutShareImportResult.Success).localId
        assertEquals(WorkoutShareKind.WORKOUT_TEMPLATE, result.kind)

        // Verificar o WorkoutTemplate importado
        val template = database.workoutDao().getTemplateById(templateId)
        assertNotNull(template)
        assertEquals("Treino A Compartilhado", template!!.name)
        assertEquals("A", template.shortIdentifier)
        assertEquals(programId, template.programId)

        // Verificar exercícios associados
        val templateExercises = database.workoutDao().getTemplateExercisesWithDetails(templateId).map { it.templateExercise }
        assertEquals(2, templateExercises.size)

        val firstEx = templateExercises.first { it.sortOrder == 0 }
        assertEquals(ex1Id, firstEx.exerciseId)
        assertEquals(4, firstEx.targetSets)
        assertEquals(8, firstEx.minReps)
        assertEquals(10, firstEx.maxReps)
        assertEquals(90, firstEx.restDurationSeconds)
        assertNull("plannedWeight não deve vir do compartilhamento", firstEx.plannedWeight)
        assertNull("machineLabel não deve vir do compartilhamento", firstEx.machineLabel)
        assertNull("notes não deve vir do compartilhamento", firstEx.notes)
        assertEquals(ex2Id, templateExercises.first { it.sortOrder == 1 }.exerciseId)

        // Verificar recibo gravado no Room
        val receipt = database.workoutShareReceiptDao().findReceipt("share-uuid-1")
        assertNotNull(receipt)
        assertEquals(templateId, receipt!!.importedTemplateLocalId)
        assertNull(receipt.importedProgramLocalId)
        assertEquals(1700000000000L, receipt.createdAt)

        // Verificar que notificou o gateway
        assertTrue(fakeGateway.completedImports.contains("share-uuid-1"))
    }

    @Test
    fun `importShare is idempotent and does not create duplicate template when already imported`() = runTest {
        seedCatalogExercise("Supino Reto", "cat-bench")
        val programId = seedProgram()

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = "Treino A",
            shortIdentifier = "A",
            exercises = listOf(
                SharedExerciseSnapshot(
                    canonicalExerciseId = "cat-bench",
                    sortOrder = 0,
                    targetSets = 3,
                    minReps = 10,
                    maxReps = 12,
                    restDurationSeconds = 60
                )
            )
        )

        val firstResult = importer.importShare("share-uuid-1", snapshot, programId)
        assertTrue(firstResult is WorkoutShareImportResult.Success)
        val templateId = (firstResult as WorkoutShareImportResult.Success).localId

        // Segunda tentativa com o mesmo shareId
        val secondResult = importer.importShare("share-uuid-1", snapshot, programId)
        assertTrue(secondResult is WorkoutShareImportResult.AlreadyImported)
        assertEquals(templateId, (secondResult as WorkoutShareImportResult.AlreadyImported).localId)

        // Garantir que não duplicou o template no programa
        val templates = database.workoutDao().getTemplatesForProgramSync(programId)
        assertEquals(1, templates.size)
    }

    @Test
    fun `importShare returns MissingExercises when canonical exercise is not in catalog`() = runTest {
        val programId = seedProgram()

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = "Treino Incompatível",
            shortIdentifier = "X",
            exercises = listOf(
                SharedExerciseSnapshot(
                    canonicalExerciseId = "cat-unknown-exercise",
                    sortOrder = 0,
                    targetSets = 3,
                    minReps = 10,
                    maxReps = 12,
                    restDurationSeconds = 60
                )
            )
        )

        val result = importer.importShare("share-uuid-missing", snapshot, programId)
        assertTrue(result is WorkoutShareImportResult.MissingExercises)
        val missing = (result as WorkoutShareImportResult.MissingExercises).missingCanonicalIds
        assertEquals(listOf("cat-unknown-exercise"), missing)

        // Nenhum recibo ou template criado
        assertNull(database.workoutShareReceiptDao().findReceipt("share-uuid-missing"))
    }

    // ------------------------------------------------------------------- atomicidade (T17.7)

    @Test
    fun `interrupcao entre o treino e o recibo nao deixa treino orfao`() = runTest {
        seedCatalogExercise("Supino Reto", "cat-bench")
        val programId = seedProgram()

        // O recibo é a idempotência desta importação. Sem transação, um treino gravado **sem** ele
        // fazia reabrir a oferta criar um segundo treino ao lado do primeiro — e assim por diante.
        val receipts = FailingReceiptDao(database.workoutShareReceiptDao())
        val transactional = WorkoutShareImporter(
            workoutRepository = transactionalRepository(),
            receiptDao = receipts,
            gateway = fakeGateway,
            clock = { 1700000000000L }
        )

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = "Treino A Compartilhado",
            shortIdentifier = "A",
            exercises = listOf(
                SharedExerciseSnapshot(
                    canonicalExerciseId = "cat-bench",
                    sortOrder = 0,
                    targetSets = 4,
                    minReps = 8,
                    maxReps = 10,
                    restDurationSeconds = 90
                )
            )
        )

        val failed = runCatching { transactional.importShare("share-atomico", snapshot, programId) }

        // A asserção é sobre o **estado do banco**, não sobre qual exceção subiu: é o estado que a
        // próxima abertura do app vai encontrar.
        assertTrue("a falha do recibo precisa desfazer a importação", failed.isFailure)
        assertEquals(1, receipts.attempts)
        assertEquals(
            "nenhum treino pela metade pode sobreviver",
            emptyList<String>(),
            database.workoutDao().getTemplatesForProgramSync(programId).map { it.name }
        )
        assertNull(database.workoutShareReceiptDao().findReceipt("share-atomico"))

        // Reabrir a oferta depois disso importa **uma** vez, e não duas.
        receipts.failing = false
        val retry = transactional.importShare("share-atomico", snapshot, programId)
        assertTrue(retry is WorkoutShareImportResult.Success)
        assertEquals(1, database.workoutDao().getTemplatesForProgramSync(programId).size)
    }

    @Test
    fun `o treino, os exercicios e o recibo entram juntos`() = runTest {
        seedCatalogExercise("Supino Reto", "cat-bench")
        seedCatalogExercise("Tríceps Corda", "cat-triceps")
        val programId = seedProgram()

        val transactional = WorkoutShareImporter(
            workoutRepository = transactionalRepository(),
            receiptDao = database.workoutShareReceiptDao(),
            gateway = fakeGateway,
            clock = { 1700000000000L }
        )

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = "Treino completo",
            shortIdentifier = "C",
            exercises = listOf(
                SharedExerciseSnapshot("cat-bench", 0, 4, 8, 10, 90),
                SharedExerciseSnapshot("cat-triceps", 1, 3, 12, 15, 60)
            )
        )

        val result = transactional.importShare("share-ok", snapshot, programId)
        val templateId = (result as WorkoutShareImportResult.Success).localId

        assertEquals(2, database.workoutDao().getTemplateExercisesWithDetails(templateId).size)
        assertEquals(
            templateId,
            database.workoutShareReceiptDao().findReceipt("share-ok")?.importedTemplateLocalId
        )
    }

    /** O mesmo repositório, com transação de verdade — como o app o monta. */
    private fun transactionalRepository() = WorkoutRepository(
        database.workoutDao(),
        syncMutations = SyncMutationCoordinator(
            transactions = RoomTransactionRunner(database),
            outboxDao = database.syncOutboxDao(),
            scopeProvider = com.example.data.sync.CloudSyncScopeProvider.Disabled
        )
    )

    /** Um DAO de recibos que recusa a gravação, para provocar a falha exatamente no passo 7. */
    private class FailingReceiptDao(
        private val real: WorkoutShareReceiptDao,
        var failing: Boolean = true
    ) : WorkoutShareReceiptDao by real {

        var attempts: Int = 0
            private set

        override suspend fun insertReceipt(receipt: WorkoutShareImportReceiptEntity) {
            attempts++
            if (failing) error("recibo indisponível")
            real.insertReceipt(receipt)
        }
    }

    @Test
    fun `importShare returns Error when no program exists and none provided`() = runTest {
        seedCatalogExercise("Supino Reto", "cat-bench")
        // No program seeded

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = "Treino Sem Programa",
            shortIdentifier = "S",
            exercises = listOf(
                SharedExerciseSnapshot(
                    canonicalExerciseId = "cat-bench",
                    sortOrder = 0,
                    targetSets = 3,
                    minReps = 10,
                    maxReps = 12,
                    restDurationSeconds = 60
                )
            )
        )

        val result = importer.importShare("share-uuid-no-prog", snapshot, null)
        assertTrue(result is WorkoutShareImportResult.Error)
    }

    // ------------------------------------------------------------------- programa (T19.3)

    private val programSnapshot = SharedProgramSnapshot(
        snapshotVersion = 1,
        name = "Push/Pull/Legs",
        description = "Três dias",
        templates = listOf(
            // Fora de ordem de propósito: a ordem que vale é `orderInProgram`.
            SharedProgramTemplateSnapshot(
                name = "Legs",
                shortIdentifier = "C",
                orderInProgram = 2,
                dayOfWeek = "Sex",
                exercises = listOf(SharedExerciseSnapshot("cat-squat", 0, 5, 5, 5, 180))
            ),
            SharedProgramTemplateSnapshot(
                name = "Push",
                shortIdentifier = "A",
                orderInProgram = 0,
                dayOfWeek = "Seg",
                exercises = listOf(
                    SharedExerciseSnapshot("cat-triceps", 1, 3, 12, 15, 60),
                    SharedExerciseSnapshot("cat-bench", 0, 4, 8, 10, 90)
                )
            ),
            SharedProgramTemplateSnapshot(
                name = "Pull",
                shortIdentifier = "B",
                orderInProgram = 1,
                dayOfWeek = null,
                exercises = listOf(SharedExerciseSnapshot("cat-row", 0, 4, 8, 12, 90))
            )
        )
    )

    private suspend fun seedProgramCatalog() {
        seedCatalogExercise("Supino Reto", "cat-bench")
        seedCatalogExercise("Tríceps Corda", "cat-triceps")
        seedCatalogExercise("Remada", "cat-row")
        seedCatalogExercise("Agachamento", "cat-squat")
    }

    @Test
    fun `importProgramShare cria programa, treinos e exercicios novos, em ordem, sem tocar o atual`() = runTest {
        seedProgramCatalog()
        val currentProgramId = seedProgram("Meu Programa Atual")
        val transactional = WorkoutShareImporter(
            workoutRepository = transactionalRepository(),
            receiptDao = database.workoutShareReceiptDao(),
            gateway = fakeGateway,
            clock = { 1700000000000L }
        )

        val result = transactional.importProgramShare("share-program-1", programSnapshot)

        assertTrue(result is WorkoutShareImportResult.Success)
        val programId = (result as WorkoutShareImportResult.Success).localId
        assertEquals(WorkoutShareKind.WORKOUT_PROGRAM, result.kind)
        assertNotEquals(currentProgramId, programId)

        // O programa novo: nome e descrição do snapshot, `isCurrent` falso, identidade própria.
        val program = database.workoutDao().getProgramById(programId)
        assertNotNull(program)
        assertEquals("Push/Pull/Legs", program!!.name)
        assertEquals("Três dias", program.description)
        assertFalse("receber um programa não o torna o atual", program.isCurrent)
        assertNull(program.externalId)
        assertTrue(program.syncId.isNotBlank())

        // O programa atual continua sendo o que era.
        assertEquals(currentProgramId, database.workoutDao().getCurrentProgramSync()?.id)

        // Os treinos, na ordem do snapshot, com posições normalizadas e dia sugerido preservado.
        val templates = database.workoutDao().getTemplatesForProgramSync(programId)
        assertEquals(listOf("Push", "Pull", "Legs"), templates.map { it.name })
        assertEquals(listOf(0, 1, 2), templates.map { it.orderInProgram })
        assertEquals(listOf("A", "B", "C"), templates.map { it.shortIdentifier })
        assertEquals(listOf("Seg", null, "Sex"), templates.map { it.dayOfWeek })
        assertEquals("cada treino tem syncId próprio", 3, templates.map { it.syncId }.toSet().size)

        // Os exercícios de cada treino, na ordem, sem carga, nota ou máquina.
        val push = database.workoutDao().getTemplateExercisesWithDetails(templates[0].id)
        assertEquals(listOf("cat-bench", "cat-triceps"), push.map { it.exercise.canonicalId })
        assertEquals(listOf(0, 1), push.map { it.templateExercise.sortOrder })
        assertEquals(4, push[0].templateExercise.targetSets)
        assertEquals(8, push[0].templateExercise.minReps)
        assertEquals(10, push[0].templateExercise.maxReps)
        assertEquals(90, push[0].templateExercise.restDurationSeconds)
        push.forEach {
            assertNull(it.templateExercise.plannedWeight)
            assertNull(it.templateExercise.machineLabel)
            assertNull(it.templateExercise.notes)
        }
        assertEquals(listOf("cat-row"), database.workoutDao().getTemplateExercisesWithDetails(templates[1].id).map { it.exercise.canonicalId })
        assertEquals(listOf("cat-squat"), database.workoutDao().getTemplateExercisesWithDetails(templates[2].id).map { it.exercise.canonicalId })

        // Nada de histórico: importar não cria sessão.
        assertNull(database.workoutDao().getLastCompletedSession())

        // O recibo aponta para o programa, e o servidor foi avisado.
        val receipt = database.workoutShareReceiptDao().findReceipt("share-program-1")
        assertEquals(programId, receipt?.importedProgramLocalId)
        assertNull(receipt?.importedTemplateLocalId)
        assertEquals(listOf("share-program-1"), fakeGateway.completedImports)
    }

    @Test
    fun `importProgramShare com um exercicio ausente nao escreve nada`() = runTest {
        seedCatalogExercise("Supino Reto", "cat-bench")
        seedCatalogExercise("Tríceps Corda", "cat-triceps")
        seedCatalogExercise("Remada", "cat-row")
        // `cat-squat` não existe neste aparelho.
        seedProgram()
        val programsBefore = database.workoutDao().countPrograms()

        val result = importer.importProgramShare("share-program-missing", programSnapshot)

        assertTrue(result is WorkoutShareImportResult.MissingExercises)
        assertEquals(listOf("cat-squat"), (result as WorkoutShareImportResult.MissingExercises).missingCanonicalIds)
        // Nem o programa, nem os dois treinos que teriam dado certo: tudo ou nada.
        assertEquals(programsBefore, database.workoutDao().countPrograms())
        assertNull(database.workoutShareReceiptDao().findReceipt("share-program-missing"))
        assertTrue(fakeGateway.completedImports.isEmpty())
    }

    @Test
    fun `falha no recibo desfaz o programa inteiro, e o retry cria um so`() = runTest {
        seedProgramCatalog()
        seedProgram()
        val receipts = FailingReceiptDao(database.workoutShareReceiptDao())
        val transactional = WorkoutShareImporter(
            workoutRepository = transactionalRepository(),
            receiptDao = receipts,
            gateway = fakeGateway,
            clock = { 1700000000000L }
        )

        val failed = runCatching { transactional.importProgramShare("share-program-atomic", programSnapshot) }

        assertTrue(failed.isFailure)
        assertEquals(1, receipts.attempts)
        // O estado do banco é o de antes: um programa (o semeado), nenhum treino do snapshot.
        assertEquals(1, database.workoutDao().countPrograms())
        assertEquals(
            emptyList<String>(),
            database.workoutDao().getAllProgramsSync().flatMap { database.workoutDao().getTemplatesForProgramSync(it.id) }.map { it.name }
        )
        assertNull(database.workoutShareReceiptDao().findReceipt("share-program-atomic"))
        assertTrue(fakeGateway.completedImports.isEmpty())

        receipts.failing = false
        val retry = transactional.importProgramShare("share-program-atomic", programSnapshot)
        assertTrue(retry is WorkoutShareImportResult.Success)
        assertEquals(2, database.workoutDao().countPrograms())
        assertEquals(3, database.workoutDao().getTemplatesForProgramSync((retry as WorkoutShareImportResult.Success).localId).size)
    }

    @Test
    fun `importProgramShare e idempotente por recibo`() = runTest {
        seedProgramCatalog()
        seedProgram()

        val first = importer.importProgramShare("share-program-idem", programSnapshot) as WorkoutShareImportResult.Success
        val second = importer.importProgramShare("share-program-idem", programSnapshot)

        assertTrue(second is WorkoutShareImportResult.AlreadyImported)
        assertEquals(first.localId, (second as WorkoutShareImportResult.AlreadyImported).localId)
        assertEquals(WorkoutShareKind.WORKOUT_PROGRAM, second.kind)
        assertEquals(2, database.workoutDao().countPrograms())
    }

    @Test
    fun `a copia importada e independente do snapshot que a originou`() = runTest {
        seedProgramCatalog()
        seedProgram()

        val programId = (importer.importProgramShare("share-program-copy", programSnapshot) as WorkoutShareImportResult.Success).localId

        // "O remetente editou o programa" é, para quem recebeu, um snapshot diferente que nunca
        // chega: nada no Room referencia a oferta além do recibo, e a cópia não muda.
        val templatesBefore = database.workoutDao().getTemplatesForProgramSync(programId)
        val edited = programSnapshot.copy(name = "PPL v2", templates = programSnapshot.templates.drop(1))
        val again = importer.importProgramShare("share-program-copy", edited)

        assertTrue(again is WorkoutShareImportResult.AlreadyImported)
        assertEquals("Push/Pull/Legs", database.workoutDao().getProgramById(programId)?.name)
        assertEquals(templatesBefore, database.workoutDao().getTemplatesForProgramSync(programId))
    }

    // ------------------------------------------------------------------- aceite (T19.3)

    @Test
    fun `acceptAndImport aceita no servidor, importa o conteudo devolvido e conclui`() = runTest {
        seedProgramCatalog()
        seedProgram()
        fakeGateway.acceptResult = WorkoutShareOutcome.Success(
            detail("share-accept", WorkoutShareContent.Program(programSnapshot))
        )

        val result = importer.acceptAndImport("share-accept")

        assertTrue(result is WorkoutShareImportResult.Success)
        assertEquals(WorkoutShareKind.WORKOUT_PROGRAM, (result as WorkoutShareImportResult.Success).kind)
        assertEquals(listOf("share-accept"), fakeGateway.acceptedShares)
        assertEquals(listOf("share-accept"), fakeGateway.completedImports)
        assertEquals(2, database.workoutDao().countPrograms())
    }

    @Test
    fun `acceptAndImport de um treino avulso segue o mesmo caminho`() = runTest {
        seedCatalogExercise("Supino Reto", "cat-bench")
        val programId = seedProgram()
        fakeGateway.acceptResult = WorkoutShareOutcome.Success(
            detail(
                "share-accept-t",
                WorkoutShareContent.Workout(
                    SharedWorkoutSnapshot(name = "Treino A", shortIdentifier = "A", exercises = listOf(SharedExerciseSnapshot("cat-bench", 0, 3, 8, 12, 90)))
                )
            )
        )

        val result = importer.acceptAndImport("share-accept-t")

        assertTrue(result is WorkoutShareImportResult.Success)
        assertEquals(WorkoutShareKind.WORKOUT_TEMPLATE, (result as WorkoutShareImportResult.Success).kind)
        assertEquals(1, database.workoutDao().getTemplatesForProgramSync(programId).size)
    }

    @Test
    fun `acceptAndImport recusado pelo servidor nao escreve nada`() = runTest {
        seedProgramCatalog()
        seedProgram()
        // Bloqueio, cancelamento e expiração chegam como recusa do servidor — e a resposta
        // certa é não criar nada.
        fakeGateway.acceptResult = WorkoutShareOutcome.Failure(WorkoutShareError.SHARE_NOT_FOUND)

        val result = importer.acceptAndImport("share-blocked")

        assertTrue(result is WorkoutShareImportResult.Rejected)
        assertEquals(WorkoutShareError.SHARE_NOT_FOUND, (result as WorkoutShareImportResult.Rejected).error)
        assertEquals(1, database.workoutDao().countPrograms())
        assertNull(database.workoutShareReceiptDao().findReceipt("share-blocked"))
        assertTrue(fakeGateway.completedImports.isEmpty())
    }

    @Test
    fun `acceptAndImport duas vezes produz uma copia, e a segunda nem consulta o servidor`() = runTest {
        seedProgramCatalog()
        seedProgram()
        fakeGateway.acceptResult = WorkoutShareOutcome.Success(
            detail("share-twice", WorkoutShareContent.Program(programSnapshot))
        )

        val first = importer.acceptAndImport("share-twice")
        // Depois da primeira cópia o servidor pode até ter cancelado a oferta (bloqueio): a
        // cópia é do destinatário, e a resposta continua vindo do recibo local.
        fakeGateway.acceptResult = WorkoutShareOutcome.Failure(WorkoutShareError.SHARE_NOT_FOUND)
        val second = importer.acceptAndImport("share-twice")

        assertTrue(first is WorkoutShareImportResult.Success)
        assertTrue(second is WorkoutShareImportResult.AlreadyImported)
        assertEquals(listOf("share-twice"), fakeGateway.acceptedShares)
        assertEquals(2, database.workoutDao().countPrograms())
    }

    @Test
    fun `acceptAndImport sem conteudo conhecido nao escreve nada`() = runTest {
        seedProgram()
        fakeGateway.acceptResult = WorkoutShareOutcome.Success(detail("share-unknown", content = null))

        val result = importer.acceptAndImport("share-unknown")

        assertTrue(result is WorkoutShareImportResult.Error)
        assertEquals(1, database.workoutDao().countPrograms())
        assertNull(database.workoutShareReceiptDao().findReceipt("share-unknown"))
    }

    @Test
    fun `retry depois de falha local passa pelo aceite de novo e importa uma vez`() = runTest {
        seedCatalogExercise("Supino Reto", "cat-bench")
        seedCatalogExercise("Tríceps Corda", "cat-triceps")
        seedCatalogExercise("Remada", "cat-row")
        seedProgram()
        fakeGateway.acceptResult = WorkoutShareOutcome.Success(
            detail("share-retry", WorkoutShareContent.Program(programSnapshot))
        )

        // Primeira tentativa: o catálogo local ainda não tem `cat-squat`.
        val first = importer.acceptAndImport("share-retry")
        assertTrue(first is WorkoutShareImportResult.MissingExercises)
        assertEquals(1, database.workoutDao().countPrograms())

        // O catálogo foi atualizado; reabrir a oferta aceita de novo (idempotente no servidor) e
        // importa **uma** vez.
        seedCatalogExercise("Agachamento", "cat-squat")
        val second = importer.acceptAndImport("share-retry")
        assertTrue(second is WorkoutShareImportResult.Success)
        assertEquals(listOf("share-retry", "share-retry"), fakeGateway.acceptedShares)
        assertEquals(2, database.workoutDao().countPrograms())
    }
}
