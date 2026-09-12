package com.example.data.sync

import android.database.sqlite.SQLiteConstraintException
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutTemplateEntity
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
 * O que o apply remoto faz quando o **banco local** recusa a escrita (auditoria 2026-09-12).
 *
 * Até esta correção, `apply()` só capturava `SyncRemoteApplyException`. Qualquer
 * `SQLiteException` — índice único, chave estrangeira, `RESTRICT` — subia por `syncNow()` e
 * `onAppForeground()` e **derrubava o processo**. O caso concreto era banal: dois aparelhos que
 * importaram o mesmo programa de manifesto antes de adotar a nuvem têm o mesmo `externalId` e
 * `syncId` diferentes, e o índice `UNIQUE` recusava a inserção a cada abertura do app, com o
 * cursor parado para sempre.
 *
 * São duas garantias distintas e as duas importam:
 *
 * 1. o caso concreto deixou de acontecer (`writeProgram` reconcilia pelo `externalId`);
 * 2. se **outro** caso aparecer, o sync pausa com motivo em vez de matar o app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncApplierResilienceTest {

    private val ownerUid = "uid-da-conta-a"

    private lateinit var server: FakeSparkSyncServer
    private lateinit var deviceA: SyncDevice
    private lateinit var deviceB: SyncDevice

    @Before
    fun setUp() {
        server = FakeSparkSyncServer()
        deviceA = SyncDevice(server, ownerUid, "device-a")
        deviceB = SyncDevice(server, ownerUid, "device-b")
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    // ------------------------------------------------------ programa importado nos dois

    @Test
    fun programaComMesmoExternalIdEReconciliadoEmVezDeColidir() = runTest {
        deviceA.bind()
        deviceB.bind()

        // O mesmo manifesto importado nos dois aparelhos, antes de a nuvem existir: identidade de
        // conteúdo igual, identidade global diferente.
        val syncIdA = "11111111-1111-4111-8111-111111111111"
        val syncIdB = "22222222-2222-4222-8222-222222222222"
        deviceA.importProgram("manifesto.abcde", name = "ABCDE Hipertrofia", syncId = syncIdA)
        deviceB.importProgram("manifesto.abcde", name = "ABCDE Hipertrofia", syncId = syncIdB)

        deviceA.recordUpsert(SyncEntityType.WORKOUT_PROGRAM, syncIdA)
        deviceA.sync()

        val programsBefore = deviceB.programCount()
        val outcome = deviceB.sync() as SyncOutcome.Success

        // Sem crash, sem pausa, e o cursor andou.
        assertNull("o apply não pode parar por causa disto", outcome.pausedAt)
        assertTrue(deviceB.cursor() > 0)

        // Uma linha só: a que já existia adotou a identidade global da nuvem.
        assertEquals(
            "reconciliar é adotar a identidade, nunca criar uma segunda linha",
            programsBefore,
            deviceB.programCount()
        )
        assertNotNull(deviceB.programBySyncId(syncIdA))
        assertNull("a identidade local anterior não sobrevive à reconciliação", deviceB.programBySyncId(syncIdB))
    }

    @Test
    fun programaSemExternalIdContinuaSendoInserido() = runTest {
        deviceA.bind()
        deviceB.bind()

        // O contraponto: sem `externalId` não há identidade de conteúdo para reconciliar, e um
        // programa novo da nuvem precisa continuar nascendo aqui. `NULL` não colide no índice
        // `UNIQUE` do SQLite, e é isso que mantém os dois caminhos separados.
        val syncId = "33333333-3333-4333-8333-333333333333"
        deviceA.createProgram(name = "Programa novo", syncId = syncId)
        deviceA.recordUpsert(SyncEntityType.WORKOUT_PROGRAM, syncId)
        deviceA.sync()

        val before = deviceB.programCount()
        val outcome = deviceB.sync() as SyncOutcome.Success

        assertNull(outcome.pausedAt)
        assertEquals(before + 1, deviceB.programCount())
        assertEquals("Programa novo", deviceB.programBySyncId(syncId)?.name)
    }

    // ------------------------------------------------------ o banco recusando a escrita

    @Test
    fun excecaoDoSqliteViraPausaComMotivoEmVezDeDerrubarOApp() = runTest {
        deviceA.bind()
        deviceB.bind()
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()

        // Um banco que recusa a inserção do treino. O tipo é o mesmo que o Room propaga quando um
        // índice único ou uma chave estrangeira barra a escrita.
        val refusing = RefusingWorkoutDao(deviceB.database.workoutDao())
        val applier = applierOver(refusing)

        val page = server.pull(ownerUid, cursor = 0, limit = 50)
        val result = applier.apply(ownerUid, page.changes)

        // O desfecho é `Failed`, não uma exceção propagada: a asserção é sobre o **estado**
        // devolvido, e não sobre a identidade de uma exceção que atravessa o SQLite.
        val stop = result.stop
        assertTrue("o apply precisa devolver uma pausa, e não estourar: $stop", stop is SyncApplyStop.Failed)
        val failed = stop as SyncApplyStop.Failed
        assertTrue(
            "o motivo precisa dizer que a escrita local foi recusada: ${failed.reason}",
            failed.reason.startsWith("LOCAL_WRITE_REFUSED")
        )
        assertEquals("a mensagem do SQLite nunca entra no motivo", 2, failed.reason.split(":").size)

        // E nada foi escrito: cursor parado, treino ausente, página inteira desfeita.
        assertNull(result.cursor)
        assertEquals(0L, deviceB.cursor())
        assertFalse(deviceB.templateExists(templateSyncId))
        assertEquals(1, refusing.attempts)
    }

    @Test
    fun depoisDaRecusaAMesmaPaginaVoltaAserAplicada() = runTest {
        deviceA.bind()
        deviceB.bind()
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()

        val refusing = RefusingWorkoutDao(deviceB.database.workoutDao(), refuse = true)
        val page = server.pull(ownerUid, cursor = 0, limit = 50)
        applierOver(refusing).apply(ownerUid, page.changes)

        // O banco volta a aceitar — o equivalente a o usuário resolver o que causava a recusa. O
        // cursor não andou, então a mesma página é pedida de novo e aplica inteira.
        refusing.refuse = false
        val second = applierOver(refusing).apply(ownerUid, page.changes)

        assertNull(second.stop)
        assertEquals(1, second.applied)
        assertTrue(deviceB.templateExists(templateSyncId))
        assertTrue(deviceB.cursor() > 0)
    }

    private fun applierOver(workoutDao: WorkoutDao): SyncRemoteApplier = SyncRemoteApplier(
        transactions = RoomTransactionRunner(deviceB.database),
        workoutDao = workoutDao,
        bodyMeasurementDao = deviceB.database.bodyMeasurementDao(),
        outboxDao = deviceB.database.syncOutboxDao(),
        metadataDao = deviceB.database.entitySyncMetadataDao(),
        cursorDao = deviceB.database.syncCursorDao(),
        conflictDao = deviceB.database.syncConflictDao(),
        snapshotBuilder = deviceB.aggregates,
        clock = { SyncDevice.CLOCK }
    )

    /**
     * Um `WorkoutDao` que recusa a inserção de treino, por delegação.
     *
     * Injetar a recusa é o único jeito honesto de exercitar este caminho: o caso real que o
     * provocava — `externalId` duplicado — foi corrigido, e provocar uma violação de restrição
     * "por acaso" provaria menos do que provocá-la exatamente onde se quer.
     */
    private class RefusingWorkoutDao(
        private val real: WorkoutDao,
        var refuse: Boolean = true
    ) : WorkoutDao by real {

        var attempts: Int = 0
            private set

        override suspend fun insertTemplate(template: WorkoutTemplateEntity): Long {
            attempts++
            if (refuse) throw SQLiteConstraintException("UNIQUE constraint failed: treino-secreto")
            return real.insertTemplate(template)
        }
    }
}
