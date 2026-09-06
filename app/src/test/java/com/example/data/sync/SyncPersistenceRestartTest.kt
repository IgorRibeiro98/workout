package com.example.data.sync

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Identidade e Outbox precisam sobreviver a fechar e reabrir o banco (T16.3).
 *
 * Um `syncId` que mudasse a cada abertura não seria identidade nenhuma, e uma Outbox que sumisse
 * ao reabrir seria fila em memória com um nome pomposo. Por isso este teste usa banco **em
 * arquivo**, fecha de verdade e reabre — não `inMemory`, que provaria o contrário do que
 * precisamos.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncPersistenceRestartTest {

    private val dbName = "sync-restart-test-db"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
        .allowMainThreadQueries()
        .build()

    @Test
    fun syncIdAndOutboxSurviveClosingAndReopeningTheDatabase() = runTest {
        context.deleteDatabase(dbName)

        val first = open()
        val programId = first.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        val repository = WorkoutRepository(
            first.workoutDao(),
            syncMutations = SyncMutationCoordinator(
                transactions = RoomTransactionRunner(first),
                outboxDao = first.syncOutboxDao(),
                scopeProvider = { CloudSyncScope.Enabled("uid-A") }
            )
        )
        val templateId = repository.addTemplate(programId, "Treino A", "A", 0)
        val templateSyncId = first.workoutDao().getTemplateById(templateId)!!.syncId
        val programSyncId = first.workoutDao().getProgramById(programId)!!.syncId
        val mutationId = first.syncOutboxDao().all().single().clientMutationId
        assertTrue(templateSyncId.isNotBlank())
        first.close()

        val second = open()
        assertEquals(templateSyncId, second.workoutDao().getTemplateById(templateId)!!.syncId)
        assertEquals(programSyncId, second.workoutDao().getProgramById(programId)!!.syncId)

        val entries = second.syncOutboxDao().pendingFor("uid-A")
        assertEquals(1, entries.size)
        assertEquals(mutationId, entries.single().clientMutationId)
        assertEquals(templateSyncId, entries.single().entitySyncId)
        assertEquals(SyncOperation.UPSERT.name, entries.single().operation)
        second.close()
    }

    @Test
    fun theDatabaseRefusesTwoEntitiesWithTheSameSyncId() = runTest {
        context.deleteDatabase(dbName)
        val database = open()
        val dao = database.workoutDao()
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        val original = WorkoutTemplateEntity(programId = programId, name = "Treino A")
        dao.insertTemplate(original)

        // Duas entidades distintas com a mesma identidade global corromperiam a convergência: o
        // servidor não teria como saber qual delas é "o treino ABC". A restrição é do banco.
        val duplicate = WorkoutTemplateEntity(
            programId = programId,
            name = "Treino B",
            syncId = original.syncId
        )
        val rejected = runCatching { dao.insertTemplate(duplicate) }.isFailure
        assertTrue("o banco deveria recusar syncId repetido", rejected)

        // E o insert recusado não deixou rastro: continua existindo um treino só.
        assertEquals(1, dao.getAllTemplatesSync().size)
        assertEquals("Treino A", dao.getAllTemplatesSync().single().name)
        database.close()
    }
}
