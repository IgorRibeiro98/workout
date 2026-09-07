package com.example.data.sync

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cursor, revision conhecida e conflitos precisam sobreviver ao processo morrer (T16.6).
 *
 * Um cursor em memória faria o aparelho reprocessar a conta inteira a cada abertura; uma revision
 * em memória faria a primeira edição depois de reabrir virar conflito; um conflito em memória
 * sumiria levando junto o único registro de que havia algo a decidir.
 *
 * Por isso este teste usa banco **em arquivo**, fecha de verdade e reabre — não `inMemory`, que
 * provaria o contrário do que precisamos.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncDurabilityTest {

    private val dbName = "sync-durability-test-db"
    private val ownerUid = "uid-da-conta"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun open(): AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, dbName)
        .allowMainThreadQueries()
        .build()

    @Test
    fun cursorRevisionEConflitoSobrevivemAoReabrir() = runTest {
        context.deleteDatabase(dbName)

        val first = open()
        first.syncCursorDao().upsert(
            SyncCursorEntity(ownerUid = ownerUid, lastPulledServerSequence = 1842, lastSyncedAt = 100)
        )
        first.entitySyncMetadataDao().upsert(
            EntitySyncMetadataEntity(
                ownerUid = ownerUid,
                entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
                entitySyncId = "ffffffff-0000-4000-8000-000000000001",
                lastKnownServerRevision = 4,
                lastSyncedPayloadHash = "hash-4",
                lastSyncedAt = 100
            )
        )
        first.syncConflictDao().upsert(
            SyncConflictEntity(
                ownerUid = ownerUid,
                entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
                entitySyncId = "ffffffff-0000-4000-8000-000000000001",
                kind = SyncConflictKind.STALE_LOCAL_CHANGE.name,
                baseRevision = 4,
                localPayloadHash = "hash-local",
                remoteRevision = 5,
                remotePayload = "{\"name\":\"remoto\"}",
                detectedAt = 100
            )
        )
        first.close()

        val second = open()
        try {
            assertEquals(1842L, second.syncCursorDao().get(ownerUid)?.lastPulledServerSequence)
            assertEquals(
                4L,
                second.entitySyncMetadataDao()
                    .get(ownerUid, SyncEntityType.WORKOUT_TEMPLATE.name, "ffffffff-0000-4000-8000-000000000001")
                    ?.lastKnownServerRevision
            )
            val conflict = second.syncConflictDao().allFor(ownerUid).single()
            assertEquals(SyncConflictKind.STALE_LOCAL_CHANGE.name, conflict.kind)
            assertNotNull(conflict.remotePayload)
        } finally {
            second.close()
        }
    }

    @Test
    fun oCursorNaoEcompartilhadoEntreContas() = runTest {
        context.deleteDatabase(dbName)
        val db = open()
        try {
            db.syncCursorDao().upsert(SyncCursorEntity("uid-A", 100, 1))
            db.syncCursorDao().upsert(SyncCursorEntity("uid-B", 5, 1))

            // Um cursor descreve a posição em **um** change log, e o change log é por conta.
            assertEquals(100L, db.syncCursorDao().get("uid-A")?.lastPulledServerSequence)
            assertEquals(5L, db.syncCursorDao().get("uid-B")?.lastPulledServerSequence)
        } finally {
            db.close()
        }
    }

    @Test
    fun aEntradaBloqueadaSobreviveEnaoVoltaParaAFilaDeEnvio() = runTest {
        context.deleteDatabase(dbName)

        val first = open()
        first.syncOutboxDao().insert(
            SyncOutboxEntryEntity(
                clientMutationId = "mutacao-1",
                ownerUid = ownerUid,
                entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
                entitySyncId = "ffffffff-0000-4000-8000-000000000001",
                operation = SyncOperation.UPSERT.name,
                createdAt = 100
            )
        )
        val id = first.syncOutboxDao().pendingFor(ownerUid).single().id
        first.syncOutboxDao().block(ownerUid, listOf(id), "STALE", 200)
        first.close()

        val second = open()
        try {
            // A alteração local não sumiu — ela é o que o usuário fez.
            assertEquals(1, second.syncOutboxDao().count())
            // E não volta sozinha para a fila: reenviar produziria a mesma recusa.
            assertEquals(0, second.syncOutboxDao().pendingCountFor(ownerUid))
            assertEquals(1, second.syncOutboxDao().blockedCountFor(ownerUid))
            val entry = second.syncOutboxDao().all().single()
            assertEquals(SyncOutboxStatus.BLOCKED.name, entry.status)
            assertEquals("STALE", entry.blockedReason)
            assertTrue(entry.attemptCount >= 1)
        } finally {
            second.close()
        }
    }

    @Test
    fun aDecisaoDoUsuarioSobreUmConflitoSobreviveAoProcessoMorrer() = runTest {
        // O cenário do §95: ele tocou em "Manter deste aparelho", a mutação nasceu na fila e o app
        // morreu antes de enviá-la. Se a decisão vivesse em memória, ela desapareceria — e o
        // usuário voltaria a ver o mesmo conflito, sem saber que já tinha decidido.
        context.deleteDatabase(dbName)
        val entitySyncId = "ffffffff-0000-4000-8000-000000000009"

        val first = open()
        val server = FakeSparkSyncServer()
        val device = SyncDevice(server, ownerUid, "device-a", database = first)
        device.bind()
        first.syncConflictDao().upsert(
            SyncConflictEntity(
                ownerUid = ownerUid,
                entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
                entitySyncId = entitySyncId,
                kind = SyncConflictKind.STALE_LOCAL_CHANGE.name,
                baseRevision = 4,
                remoteRevision = 5,
                remotePayloadHash = "hash-remoto",
                remotePayload = "{}",
                detectedAt = 100
            )
        )

        val resolution = device.resolve(
            SyncConflictId(SyncEntityType.WORKOUT_TEMPLATE.name, entitySyncId),
            SyncConflictChoice.KEEP_LOCAL
        )
        assertEquals(SyncConflictResolution.Queued, resolution)
        first.close()

        val second = open()
        try {
            // A mutação continua na fila, com identidade própria...
            val pendentes = second.syncOutboxDao().pendingFor(ownerUid)
            assertEquals(1, pendentes.size)
            assertEquals(entitySyncId, pendentes.single().entitySyncId)
            // ...o conflito lembra que já foi decidido, então um toque novo não duplica nada...
            assertEquals(
                SyncConflictStatus.AWAITING_PUSH.name,
                second.syncConflictDao().allFor(ownerUid).single().status
            )
            // ...e a base da próxima tentativa é a revision remota que o usuário viu e recusou.
            assertEquals(
                5L,
                second.entitySyncMetadataDao()
                    .get(ownerUid, SyncEntityType.WORKOUT_TEMPLATE.name, entitySyncId)
                    ?.lastKnownServerRevision
            )
        } finally {
            second.close()
        }
    }
}
