package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareOutcome
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutShareImporterTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var fakeGateway: FakeWorkoutShareGateway
    private lateinit var importer: WorkoutShareImporter

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeWorkoutShareGateway : WorkoutShareGateway {
        override val isConfigured: Boolean = true
        val completedImports = mutableListOf<String>()

        override suspend fun createShare(
            recipientSocialId: String,
            clientRequestId: String,
            snapshot: SharedWorkoutSnapshot
        ): WorkoutShareOutcome<WorkoutShareDetail> = throw UnsupportedOperationException()

        override suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(emptyList())

        override suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>> =
            WorkoutShareOutcome.Success(emptyList())

        override suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
            throw UnsupportedOperationException()

        override suspend fun acceptShare(shareId: String): WorkoutShareOutcome<SharedWorkoutSnapshot> =
            throw UnsupportedOperationException()

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
        val templateId = (result as WorkoutShareImportResult.Success).localTemplateId

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

        // Verificar recibo gravado no Room
        val receipt = database.workoutShareReceiptDao().findReceipt("share-uuid-1")
        assertNotNull(receipt)
        assertEquals(templateId, receipt!!.importedTemplateLocalId)
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
        val templateId = (firstResult as WorkoutShareImportResult.Success).localTemplateId

        // Segunda tentativa com o mesmo shareId
        val secondResult = importer.importShare("share-uuid-1", snapshot, programId)
        assertTrue(secondResult is WorkoutShareImportResult.AlreadyImported)
        assertEquals(templateId, (secondResult as WorkoutShareImportResult.AlreadyImported).localTemplateId)

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
}
