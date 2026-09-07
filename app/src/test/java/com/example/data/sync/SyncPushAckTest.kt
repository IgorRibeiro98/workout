package com.example.data.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.BodyMeasurementEntity
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
 * O push da Outbox e o que acontece com cada desfecho (T16.6).
 *
 * A regra que estes testes protegem é uma só: **uma alteração local só sai da fila com
 * confirmação do servidor**. Tudo o mais — erro de rede, resposta perdida, escrita stale — deixa a
 * alteração exatamente onde estava, porque reenviar é barato e perder não tem conserto.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncPushAckTest {

    private val ownerUid = "uid-da-conta-a"

    private lateinit var server: FakeSparkSyncServer
    private lateinit var device: SyncDevice

    @Before
    fun setUp() {
        server = FakeSparkSyncServer()
        device = SyncDevice(server, ownerUid, "device-a")
    }

    @After
    fun tearDown() = device.close()

    // ------------------------------------------------------------------ pré-condições

    @Test
    fun datasetSemVinculoNaoSincroniza() = runTest {
        // Sem adoção não há sync — nem com sessão conectada. Login não liga a nuvem.
        val outcome = device.sync()

        assertEquals(SyncOutcome.NotEnabled, outcome)
        assertEquals(0, device.api.pushCalls)
        assertEquals(0, device.api.pullCalls)
    }

    @Test
    fun adocaoEmAndamentoAindaNaoSincroniza() = runTest {
        device.bind(CloudSyncState.PREPARING)

        assertEquals(SyncOutcome.NotEnabled, device.sync())
        assertEquals(0, device.api.pushCalls)
    }

    @Test
    fun contaDiferenteDoVinculoNaoSincroniza() = runTest {
        device.bind()

        val outcome = device.sync(currentUid = "uid-de-outra-conta")

        assertEquals(SyncOutcome.AccountMismatch(ownerUid), outcome)
        assertEquals(0, device.api.pushCalls)
        assertEquals(0, device.api.pullCalls)
    }

    @Test
    fun semSessaoNaoSincroniza() = runTest {
        device.bind()

        assertEquals(SyncOutcome.AuthRequired, device.sync(currentUid = null))
        assertEquals(0, device.api.pushCalls)
    }

    // ------------------------------------------------------------------ ACK

    @Test
    fun pendenteViraConfirmadaComRevisionConhecida() = runTest {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        assertEquals(1, device.pendingCount())

        val outcome = device.sync()

        assertTrue(outcome is SyncOutcome.Success)
        // A entrada sai da fila **e** a revision remota passa a ser conhecida — juntas.
        assertEquals(0, device.pendingCount())
        assertEquals(1L, device.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(1L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
    }

    @Test
    fun segundaEdicaoUsaARevisionConhecidaComoBase() = runTest {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        device.renameTemplate(templateSyncId, "Treino A avançado")
        device.sync()

        assertEquals(2L, device.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals("Treino A avançado", payloadField(
            server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId),
            "name"
        ))
    }

    // ------------------------------------------------------------------ resposta perdida

    @Test
    fun respostaPerdidaMantemAFilaEOReenvioEIdempotente() = runTest {
        device.bind()
        val syncId = device.newMeasurement(weightKg = 80.5f)

        // O servidor aplica; a resposta não chega.
        device.api.dropNextPushResponse = true
        assertEquals(SyncOutcome.Offline, device.sync())

        // A entrada continua pendente: o app não confirma o que não viu confirmar.
        assertEquals(1, device.pendingCount())
        assertNull(device.revisionOf(SyncEntityType.BODY_MEASUREMENT, syncId))
        assertEquals(1, server.changeCount)

        // O retry manda o **mesmo** clientMutationId e volta como já aplicada.
        device.sync()

        assertEquals(0, device.pendingCount())
        assertEquals(1L, device.revisionOf(SyncEntityType.BODY_MEASUREMENT, syncId))
        // E o servidor continua com **uma** medida e **uma** mudança. Nada duplicou.
        assertEquals(1, server.changeCount)
        assertEquals(1L, server.revisionOf(ownerUid, SyncEntityType.BODY_MEASUREMENT, syncId))
    }

    @Test
    fun erroHttpNaoApagaAOutbox() = runTest {
        device.bind()
        device.newTemplate("Treino A")

        device.api.unavailableOnNextPush = true
        assertEquals(SyncOutcome.Unavailable, device.sync())

        assertEquals(1, device.pendingCount())
        assertEquals(0, device.blockedCount())
        assertEquals(0, server.changeCount)
    }

    @Test
    fun offlineAcumulaEVoltaAFuncionar() = runTest {
        device.bind()
        device.api.offline = true

        device.newTemplate("Treino A")
        device.newTemplate("Treino B")
        device.newMeasurement(weightKg = 79f)

        assertEquals(SyncOutcome.Offline, device.sync())
        assertEquals(3, device.pendingCount())

        device.api.offline = false
        val outcome = device.sync() as SyncOutcome.Success

        assertEquals(3, outcome.pushed)
        assertEquals(0, device.pendingCount())
    }

    // ------------------------------------------------------------------ stale

    @Test
    fun escritaStaleNaoSobrescreveORemotoENaoSomeLocalmente() = runTest {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        // Outro aparelho escreve por cima, direto no servidor.
        val other = SyncDevice(server, ownerUid, "device-b")
        try {
            other.bind()
            other.sync()
            other.renameTemplate(templateSyncId, "Escrito por B")
            other.sync()
        } finally {
            other.close()
        }

        // A continua achando que a revision é 1 e edita em cima dela.
        device.renameTemplate(templateSyncId, "Escrito por A")
        val outcome = device.sync() as SyncOutcome.Success

        assertEquals(1, outcome.conflicts)
        // O remoto não foi sobrescrito.
        assertEquals(
            "Escrito por B",
            payloadField(server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId), "name")
        )
        // O local não foi descartado.
        assertEquals("Escrito por A", device.templateName(templateSyncId))
        // A alteração local continua guardada, fora da fila de envio.
        assertEquals(0, device.pendingCount())
        assertEquals(1, device.blockedCount())

        // Um item, não dois: o push detectou (stale) e o pull completou com o lado remoto.
        val conflict = device.conflicts().single()
        assertEquals(SyncConflictKind.STALE_LOCAL_CHANGE.name, conflict.kind)
        assertEquals(templateSyncId, conflict.entitySyncId)
        assertEquals(1L, conflict.baseRevision)
        assertNotNull(conflict.localPayloadHash)
        // E os dois lados estão guardados para a T16.7 decidir.
        assertEquals(2L, conflict.remoteRevision)
        assertTrue(conflict.remotePayload!!.contains("Escrito por B"))
    }

    @Test
    fun staleNaoAtualizaARevisionConhecida() = runTest {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        val other = SyncDevice(server, ownerUid, "device-b")
        try {
            other.bind()
            other.sync()
            other.renameTemplate(templateSyncId, "Escrito por B")
            other.sync()
        } finally {
            other.close()
        }

        device.renameTemplate(templateSyncId, "Escrito por A")
        device.sync()

        // Gravar a revision do servidor aqui faria o próximo push nascer com a base dele — e
        // sobrescrever a alteração do outro aparelho. É o last-write-wins que esta tarefa proíbe.
        assertEquals(1L, device.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
    }

    @Test
    fun umConflitoNaoBloqueiaOsOutrosAgregados() = runTest {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        val other = SyncDevice(server, ownerUid, "device-b")
        try {
            other.bind()
            other.sync()
            other.renameTemplate(templateSyncId, "Escrito por B")
            other.sync()
        } finally {
            other.close()
        }

        device.renameTemplate(templateSyncId, "Escrito por A")
        val measurementSyncId = device.newMeasurement(weightKg = 81f)

        val outcome = device.sync() as SyncOutcome.Success

        assertEquals(1, outcome.conflicts)
        // A medida, que não tem nada a ver com o treino em conflito, subiu normalmente.
        assertEquals(1L, device.revisionOf(SyncEntityType.BODY_MEASUREMENT, measurementSyncId))
        assertNotNull(server.revisionOf(ownerUid, SyncEntityType.BODY_MEASUREMENT, measurementSyncId))
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun exclusaoLocalNaoViraUpsertRemotoEFicaPendente() = runTest {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        device.deleteTemplate(templateSyncId)
        val outcome = device.sync() as SyncOutcome.Success

        // A intenção de exclusão continua guardada, sem virar outra coisa.
        assertTrue(outcome.deferredDeletes > 0)
        assertEquals(0, outcome.pushed)
        // E o treino **não** foi recriado no servidor pela `UPSERT` anterior a ela.
        assertEquals(1L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(1, server.changeCount)
    }

    // ------------------------------------------------------------------ ciclo

    @Test
    fun doisCiclosSimultaneosProduzemUm() = runTest {
        device.bind()
        device.newTemplate("Treino A")

        // A trava é a **mesma** que o backup e o restore usam. Com ela ocupada — por um ciclo, um
        // backup ou um restore — o ciclo novo é recusado em vez de rodar sobre o mesmo banco.
        val blocked = device.repository.let { repository ->
            device.operationLock.tryRun { repository.syncNow(ownerUid) }
        }

        assertEquals(SyncOutcome.AlreadyRunning, blocked)
        assertEquals(0, device.api.pushCalls)
        assertEquals(1, device.pendingCount())
    }
}
