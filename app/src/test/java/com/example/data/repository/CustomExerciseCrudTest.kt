package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.CloudSyncScope
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncMutationCoordinator
import com.example.data.sync.SyncOperation
import kotlinx.coroutines.flow.first
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
 * O CRUD de exercício `CUSTOM` alinhado ao domínio (T19.7C): só o nome é obrigatório, o catálogo
 * canônico não é editável por aqui, e excluir respeita quem ainda aponta para o exercício.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class CustomExerciseCrudTest {

    private lateinit var database: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val ownerUid = "uid-do-usuario"

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

    private fun repository() = WorkoutRepository(
        database.workoutDao(),
        syncMutations = SyncMutationCoordinator(
            transactions = RoomTransactionRunner(database),
            outboxDao = database.syncOutboxDao(),
            scopeProvider = { CloudSyncScope.Enabled(ownerUid) },
            clock = { 1_700_000_000_000L }
        )
    )

    private suspend fun outboxFor(syncId: String) = database.syncOutboxDao().pendingFor(ownerUid)
        .filter { it.entityType == SyncEntityType.CUSTOM_EXERCISE.name && it.entitySyncId == syncId }

    @Test
    fun `criar exige so o nome e guarda campos em branco como nulos`() = runTest {
        val repository = repository()
        val id = repository.addExercise(name = "  Meu supino  ", muscle = "", equipment = "   ", description = null)

        val saved = database.workoutDao().getExerciseById(id)
        assertNotNull(saved)
        assertEquals("Meu supino", saved!!.name)
        assertNull(saved.primaryMuscle)
        assertNull(saved.equipment)
        assertNull(saved.description)
        assertTrue(saved.isUserCreated)
        assertNull(saved.canonicalId)
        assertNotNull(saved.syncId)
        assertEquals(1, outboxFor(saved.syncId!!).size)

        val rejected = runCatching { repository.addExercise(name = "   ") }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, database.workoutDao().getActiveExercises().first().size)
    }

    @Test
    fun `editar CUSTOM muda a linha, registra uma mutacao e limpa o override sobreposto`() = runTest {
        val repository = repository()
        val id = repository.addExercise(name = "Remada X", muscle = "Costas", equipment = "Cabo")
        val syncId = database.workoutDao().getExerciseById(id)!!.syncId!!
        // Um override do caminho antigo de "personalizar" — nome e notas sobrepostos, foto mantida.
        database.workoutDao().insertOrUpdateOverride(
            ExerciseUserOverrideEntity(exerciseId = id, displayName = "Remada velha", notes = "pino 4", customPhotoUri = "content://foto", defaultRestSeconds = 75)
        )

        val applied = repository.updateCustomExercise(id, name = "Remada unilateral", muscle = "Dorsal", equipment = "Halteres", description = "Apoio no banco")

        assertTrue(applied)
        val updated = database.workoutDao().getExerciseById(id)!!
        assertEquals("Remada unilateral", updated.name)
        assertEquals("Dorsal", updated.primaryMuscle)
        assertEquals("Halteres", updated.equipment)
        assertEquals("Apoio no banco", updated.description)
        assertEquals(syncId, updated.syncId)
        assertTrue(updated.isUserCreated)

        val override = database.workoutDao().getOverrideForExercise(id)!!
        assertNull(override.displayName)
        assertNull(override.notes)
        assertEquals("content://foto", override.customPhotoUri)
        assertEquals(75, override.defaultRestSeconds)

        // Criar + editar coalescem numa única mutação pendente do mesmo agregado.
        assertEquals(1, outboxFor(syncId).size)
    }

    @Test
    fun `exercicio canonico nao e editado nem excluido por aqui`() = runTest {
        val repository = repository()
        val canonicalId = database.workoutDao().insertExercise(
            ExerciseEntity(name = "Supino reto com barra", canonicalId = "supino-reto-barra", primaryMuscle = "Peitoral", equipment = "Barra")
        )

        assertFalse(repository.updateCustomExercise(canonicalId, name = "Outro nome", muscle = null, equipment = null, description = null))
        val untouched = database.workoutDao().getExerciseById(canonicalId)!!
        assertEquals("Supino reto com barra", untouched.name)

        assertEquals(CustomExerciseDeleteResult.NotCustom, repository.deleteExercise(untouched))
        assertNotNull(database.workoutDao().getExerciseById(canonicalId))
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun `excluir CUSTOM usado em treino e recusado sem tocar em nada`() = runTest {
        val repository = repository()
        val id = repository.addExercise(name = "Meu exercício")
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "P", isCurrent = true))
        val templateId = database.workoutDao().insertTemplate(WorkoutTemplateEntity(programId = programId, name = "A"))
        database.workoutDao().insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = id, sortOrder = 0))
        val before = database.syncOutboxDao().count()

        val result = repository.deleteExercise(database.workoutDao().getExerciseById(id)!!)

        assertEquals(CustomExerciseDeleteResult.UsedByTemplates(1), result)
        val still = database.workoutDao().getExerciseById(id)
        assertNotNull(still)
        assertTrue(still!!.active)
        assertEquals(before, database.syncOutboxDao().count())
    }

    @Test
    fun `excluir CUSTOM com historico arquiva em vez de apagar`() = runTest {
        val repository = repository()
        val id = repository.addExercise(name = "Meu exercício")
        val syncId = database.workoutDao().getExerciseById(id)!!.syncId!!
        val sessionId = database.workoutDao().insertSession(
            WorkoutSessionEntity(templateId = null, startedAt = 1L, finishedAt = 2L, status = SessionStatus.COMPLETED.name)
        )
        database.workoutDao().insertExerciseSession(
            ExerciseSessionEntity(sessionId = sessionId, plannedExerciseId = id, actualExerciseId = id, exerciseNameSnapshot = "Meu exercício")
        )

        val result = repository.deleteExercise(database.workoutDao().getExerciseById(id)!!)

        assertEquals(CustomExerciseDeleteResult.Archived, result)
        val archived = database.workoutDao().getExerciseById(id)
        assertNotNull("a linha fica para o histórico", archived)
        assertFalse(archived!!.active)
        assertTrue(database.workoutDao().getActiveExercises().first().none { it.id == id })
        // O histórico continua apontando para a mesma linha.
        assertEquals(1, database.workoutDao().countSessionReferencesToExercise(id))
        // Arquivar é um UPSERT do agregado (o `active` viaja nele), não um tombstone.
        val entries = outboxFor(syncId)
        assertEquals(1, entries.size)
        assertEquals(SyncOperation.UPSERT.name, entries.single().operation)
    }

    @Test
    fun `excluir CUSTOM sem referencia apaga e registra tombstone`() = runTest {
        val repository = repository()
        val id = repository.addExercise(name = "Descartável")
        val syncId = database.workoutDao().getExerciseById(id)!!.syncId!!

        val result = repository.deleteExercise(database.workoutDao().getExerciseById(id)!!)

        assertEquals(CustomExerciseDeleteResult.Deleted, result)
        assertNull(database.workoutDao().getExerciseById(id))
        // O UPSERT da criação continua pendente (um DELETE nunca absorve o UPSERT anterior), e o
        // tombstone vem depois dele.
        val entries = outboxFor(syncId)
        assertEquals(SyncOperation.DELETE.name, entries.last().operation)
        assertEquals(1, entries.count { it.operation == SyncOperation.DELETE.name })
    }
}
