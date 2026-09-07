package com.example.data.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * A resolução explícita de conflito (T16.7).
 *
 * ```text
 * A e B na revision 4
 * A edita  →  servidor 5
 * B edita sobre 4  →  STALE  →  conflito com os dois lados guardados
 *                                   ↓
 *                    o usuário escolhe — e só ele
 * ```
 *
 * O que estes testes protegem é o que **não** pode acontecer: um lado sumir sem alguém ter
 * escolhido, uma escolha virar duas mutações, uma decisão tomada sobre uma tela desatualizada
 * sobrescrever o trabalho de um terceiro aparelho.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncConflictResolutionTest {

    private val ownerUid = "uid-da-conta-a"

    private lateinit var server: FakeSparkSyncServer
    private lateinit var a: SyncDevice
    private lateinit var b: SyncDevice

    @Before
    fun setUp() = runTest {
        server = FakeSparkSyncServer()
        a = SyncDevice(server, ownerUid, "device-a")
        b = SyncDevice(server, ownerUid, "device-b")
        a.bind()
        b.bind()
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    /** O cenário canônico: os dois partem da mesma revision e editam sem sincronizar no meio. */
    private suspend fun conflitoDeEdicaoConcorrente(): String {
        val templateSyncId = a.newTemplate("Base")
        a.sync()
        b.sync()

        a.renameTemplate(templateSyncId, "Versão do A")
        b.renameTemplate(templateSyncId, "Versão do B")
        a.sync()
        b.sync()

        return templateSyncId
    }

    // ------------------------------------------------------------------ o que a tela vê

    @Test
    fun oConflitoChegaNaTelaComOsDoisLadosEAsEscolhas() = runTest {
        conflitoDeEdicaoConcorrente()

        val summary = b.conflictSummaries().single()

        assertEquals("Versão do B", summary.title)
        assertEquals(SyncConflictCategory.CHANGED_ON_BOTH, summary.category)
        assertEquals(
            listOf(SyncConflictChoice.KEEP_LOCAL, SyncConflictChoice.USE_REMOTE),
            summary.choices
        )
        // A diferença é mostrada em vocabulário de produto — nunca `baseRevision=4`.
        val nome = summary.differences.single { it.label == "Nome" }
        assertEquals("Versão do B", nome.local)
        assertEquals("Versão do A", nome.remote)
    }

    @Test
    fun conflitoDeContaNaoVazaParaOutraConta() = runTest {
        conflitoDeEdicaoConcorrente()

        // A sessão é de outra conta: a lista fica vazia, e resolver é recusado.
        assertEquals(emptyList<SyncConflictSummary>(), b.repository.conflicts("uid-de-outra-conta"))

        val id = b.conflicts().single().let { SyncConflictId(it.entityType, it.entitySyncId) }
        val resolution = b.resolve(id, SyncConflictChoice.KEEP_LOCAL, currentUid = "uid-de-outra-conta")

        assertEquals(SyncConflictResolution.AccountMismatch, resolution)
        assertEquals(1, b.conflicts().size)
    }

    // ------------------------------------------------------------------ manter o local

    @Test
    fun manterOLocalGeraMutacaoNovaEConvergeParaB() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val bloqueada = b.database.syncOutboxDao().all().single { it.status == "BLOCKED" }

        val summary = b.conflictSummaries().single()
        assertEquals(SyncConflictResolution.Queued, b.resolve(summary.id, SyncConflictChoice.KEEP_LOCAL))

        // A tentativa recusada foi descartada e nasceu **uma** mutação nova — com identidade nova.
        val pendentes = b.database.syncOutboxDao().pendingFor(ownerUid)
        assertEquals(1, pendentes.size)
        assertFalse(
            "reaproveitar o clientMutationId faria o servidor devolver o resultado antigo",
            pendentes.single().clientMutationId == bloqueada.clientMutationId
        )
        assertEquals(0, b.blockedCount())

        b.sync()
        a.sync()

        // Todos convergem para B, e o servidor tem uma revision **nova** — não a de B por cima da
        // de A por decreto.
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals("Versão do B", a.templateName(templateSyncId))
        assertEquals(3L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(0, b.conflicts().size)
    }

    @Test
    fun manterOLocalUsaARevisionRemotaAtualComoBase() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val summary = b.conflictSummaries().single()

        b.resolve(summary.id, SyncConflictChoice.KEEP_LOCAL)

        // A base da mutação nova é a revision que o usuário viu e recusou. Sem esse rebase, o push
        // voltaria stale para sempre — e com ele "manter o meu" continua sendo uma decisão sobre
        // um estado conhecido, não um `overwrite=true`.
        assertEquals(2L, b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
    }

    @Test
    fun toqueDuploEmManterOLocalProduzUmaMutacaoSo() = runTest {
        conflitoDeEdicaoConcorrente()
        val summary = b.conflictSummaries().single()

        val primeira = b.resolve(summary.id, SyncConflictChoice.KEEP_LOCAL)
        val segunda = b.resolve(summary.id, SyncConflictChoice.KEEP_LOCAL)

        assertEquals(SyncConflictResolution.Queued, primeira)
        assertEquals(SyncConflictResolution.AlreadyResolved, segunda)
        assertEquals(1, b.database.syncOutboxDao().pendingFor(ownerUid).size)
    }

    @Test
    fun umaDecisaoJaTomadaSobreviveAoProcesso() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val summary = b.conflictSummaries().single()
        b.resolve(summary.id, SyncConflictChoice.KEEP_LOCAL)

        // O app morre antes de enviar. Um novo `SyncDevice` sobre o **mesmo** banco é o que
        // acontece na abertura seguinte.
        val reaberto = SyncDevice(server, ownerUid, "device-b", database = b.database)
        try {
            // A intenção continua na fila, e o conflito continua marcado como já decidido.
            assertEquals(1, reaberto.database.syncOutboxDao().pendingFor(ownerUid).size)
            assertEquals(
                SyncConflictStatus.AWAITING_PUSH.name,
                reaberto.conflicts().single().status
            )
            // E tocar de novo não cria uma segunda mutação.
            val id = SyncConflictId(SyncEntityType.WORKOUT_TEMPLATE.name, templateSyncId)
            assertEquals(
                SyncConflictResolution.AlreadyResolved,
                reaberto.resolve(id, SyncConflictChoice.KEEP_LOCAL)
            )

            reaberto.sync()
            assertEquals("Versão do B", reaberto.templateName(templateSyncId))
            assertEquals(0, reaberto.conflicts().size)
        } finally {
            reaberto.closeWithoutDatabase()
        }
    }

    @Test
    fun remotoMudouDeNovoEnquantoOUsuarioDecidiaProduzNovoStale() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val summary = b.conflictSummaries().single()

        // Um terceiro aparelho escreve enquanto a tela de conflito está aberta.
        val c = SyncDevice(server, ownerUid, "device-c")
        try {
            c.bind()
            c.sync()
            c.renameTemplate(templateSyncId, "Versão do C")
            c.sync()
        } finally {
            c.close()
        }

        b.resolve(summary.id, SyncConflictChoice.KEEP_LOCAL)
        b.sync()

        // A decisão de B foi construída sobre a revision 2, e o servidor está na 3. Ela **não**
        // sobrescreve o que C fez: volta a ser conflito, agora com a versão de C guardada.
        assertEquals(
            "Versão do C",
            payloadField(server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId), "name")
        )
        val conflict = b.conflicts().single()
        assertEquals(SyncConflictStatus.PENDING.name, conflict.status)
        assertEquals(3L, conflict.remoteRevision)
        assertEquals("Versão do B", b.templateName(templateSyncId))
    }

    // ------------------------------------------------------------------ usar o remoto

    @Test
    fun usarANuvemAplicaLocalmenteENaoGeraMutacao() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val summary = b.conflictSummaries().single()
        val mudancasAntes = server.changeCount

        assertEquals(SyncConflictResolution.Applied, b.resolve(summary.id, SyncConflictChoice.USE_REMOTE))

        assertEquals("Versão do A", b.templateName(templateSyncId))
        // Nenhuma mutação de saída: devolver ao servidor o que veio dele seria um laço.
        assertEquals(0, b.pendingCount())
        assertEquals(0, b.blockedCount())
        assertEquals(0, b.conflicts().size)
        assertEquals(2L, b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))

        b.sync()
        assertEquals(mudancasAntes, server.changeCount)
        assertEquals("Versão do A", b.templateName(templateSyncId))
    }

    @Test
    fun usarANuvemSemCopiaGuardadaERecusadoEmVezDeAdivinhar() = runTest {
        val templateSyncId = a.newTemplate("Base")
        a.sync()
        b.sync()

        // B fica offline: ele detecta o stale no push, mas nunca recebe o conteúdo remoto.
        a.renameTemplate(templateSyncId, "Versão do A")
        a.sync()
        b.renameTemplate(templateSyncId, "Versão do B")
        b.api.offline = false
        // Um push seguido de pull que falha deixa o conflito sem lado remoto.
        b.repository.syncNow(ownerUid)
        b.database.syncConflictDao().upsert(
            b.conflicts().single().copy(remotePayload = null, remotePayloadHash = null)
        )

        val id = SyncConflictId(SyncEntityType.WORKOUT_TEMPLATE.name, templateSyncId)
        assertEquals(SyncConflictResolution.NotAvailable, b.resolve(id, SyncConflictChoice.USE_REMOTE))
        // Nada foi aplicado, e o conflito continua inteiro.
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(1, b.conflicts().size)
    }

    // ------------------------------------------------------------------ exclusão

    @Test
    fun confirmarExclusaoRemotaApagaLocalmenteSemNovaMutacao() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        b.renameTemplate(templateSyncId, "Editado por B")
        a.deleteTemplate(templateSyncId)
        a.sync()
        b.sync()

        val mudancasAntes = server.changeCount
        val summary = b.conflictSummaries().single()
        assertEquals(SyncConflictCategory.DELETED_ELSEWHERE, summary.category)

        assertEquals(
            SyncConflictResolution.Applied,
            b.resolve(summary.id, SyncConflictChoice.CONFIRM_REMOTE_DELETE)
        )

        assertFalse(b.templateExists(templateSyncId))
        assertEquals(0, b.pendingCount())
        assertEquals(0, b.blockedCount())
        assertEquals(0, b.conflicts().size)
        b.sync()
        assertEquals("nada novo sobe: o servidor já tem o tombstone", mudancasAntes, server.changeCount)
    }

    @Test
    fun manterOItemApagadoNaNuvemCriaUmaEntidadeNova() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        b.renameTemplate(templateSyncId, "Ainda quero este")
        a.deleteTemplate(templateSyncId)
        a.sync()
        b.sync()

        val summary = b.conflictSummaries().single()
        assertTrue(summary.choices.contains(SyncConflictChoice.KEEP_LOCAL_AS_NEW))

        assertEquals(
            SyncConflictResolution.Queued,
            b.resolve(summary.id, SyncConflictChoice.KEEP_LOCAL_AS_NEW)
        )

        // A identidade morta **não** é reaproveitada: o tombstone continua significando o que
        // significava, e o que a pessoa manteve é um item novo.
        assertFalse(b.templateExists(templateSyncId))
        val nova = b.database.syncOutboxDao().pendingFor(ownerUid).single()
        assertFalse(nova.entitySyncId == templateSyncId)
        assertEquals("Ainda quero este", b.templateName(nova.entitySyncId))

        b.sync()
        a.sync()

        // O outro aparelho recebe o item novo, e o antigo continua apagado nos dois.
        assertEquals("Ainda quero este", a.templateName(nova.entitySyncId))
        assertFalse(a.templateExists(templateSyncId))
        assertTrue(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
    }

    @Test
    fun excluirMesmoAssimReenviaAExclusaoComABaseAtual() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        b.renameTemplate(templateSyncId, "Versão de B")
        b.sync()
        a.deleteTemplate(templateSyncId)
        a.sync()

        val summary = a.conflictSummaries().single()
        assertEquals(SyncConflictCategory.DELETED_HERE, summary.category)

        assertEquals(
            SyncConflictResolution.Queued,
            a.resolve(summary.id, SyncConflictChoice.CONFIRM_LOCAL_DELETE)
        )
        a.sync()
        b.sync()

        assertTrue(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertFalse(b.templateExists(templateSyncId))
        assertEquals(0, a.conflicts().size)
    }

    @Test
    fun manterAVersaoDaNuvemDepoisDeApagarLocalmenteRecriaOItem() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        b.renameTemplate(templateSyncId, "Versão de B")
        b.sync()
        a.deleteTemplate(templateSyncId)
        a.sync()
        // O pull traz a versão de B, que o conflito passa a guardar.
        a.sync()

        val summary = a.conflictSummaries().single()
        assertTrue(summary.choices.contains(SyncConflictChoice.USE_REMOTE))

        assertEquals(SyncConflictResolution.Applied, a.resolve(summary.id, SyncConflictChoice.USE_REMOTE))

        // O item volta a existir aqui, com o conteúdo da nuvem — e sem gerar mutação.
        assertEquals("Versão de B", a.templateName(templateSyncId))
        assertEquals(0, a.pendingCount())
        assertEquals(0, a.conflicts().size)
    }

    // ------------------------------------------------------------------ isolamento

    @Test
    fun umConflitoNaoBloqueiaOsOutrosAgregados() = runTest {
        conflitoDeEdicaoConcorrente()

        val outro = b.newTemplate("Treino independente")
        val medida = b.newMeasurement(weightKg = 78f)
        b.sync()
        a.sync()

        assertEquals("Treino independente", a.templateName(outro))
        assertEquals(78f, a.measurementWeight(medida))
        // E o conflito continua exatamente onde estava, esperando decisão.
        assertEquals(1, b.conflicts().size)
    }

    @Test
    fun oCicloSeguinteNaoResolveConflitoSozinho() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()

        repeat(3) {
            b.sync()
            a.sync()
        }

        // Ninguém escolheu: nada muda de lado nenhum.
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals("Versão do A", a.templateName(templateSyncId))
        assertEquals(1, b.conflicts().size)
        assertEquals(SyncConflictStatus.PENDING.name, b.conflicts().single().status)
    }

    @Test
    fun historicoDivergenteNaoOfereceEscolhaEntreVersoes() = runTest {
        val exerciseId = a.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        b.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        val sessionSyncId = a.completeSession(exerciseId, weight = 100f)
        a.sync()
        b.sync()

        // B "reescreve" a sessão concluída — o defeito que o protocolo precisa recusar.
        b.mutations.mutate {
            val session = b.database.workoutDao().getSessionWithDetailsBySyncId(sessionSyncId)!!
            val set = session.sortedExercises.first().sets.first()
            b.database.workoutDao().insertSetLogs(listOf(set.copy(id = 0, setNumber = 2, weight = 999f)))
            upsert(SyncEntityType.WORKOUT_SESSION, sessionSyncId)
        }
        b.sync()

        val summary = b.conflictSummaries().single()

        assertEquals(SyncConflictCategory.HISTORY_MISMATCH, summary.category)
        assertEquals(emptyList<SyncConflictChoice>(), summary.choices)
        // E nenhuma escolha é aceita nem por chamada direta.
        assertEquals(
            SyncConflictResolution.NotAvailable,
            b.resolve(summary.id, SyncConflictChoice.USE_REMOTE)
        )
        assertNotNull(a.database.workoutDao().getSessionIdBySyncId(sessionSyncId))
        assertEquals(1L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_SESSION, sessionSyncId))
    }

    @Test
    fun historicoIdenticoNaoViraConflito() = runTest {
        val exerciseId = a.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        b.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        a.completeSession(exerciseId, weight = 100f)
        a.sync()

        // B recebe e reenvia o mesmo conteúdo: idempotente, sem revision nova e sem conflito.
        b.sync()
        b.sync()

        assertEquals(0, b.conflicts().size)
        assertNull(b.conflicts().firstOrNull())
        assertEquals(1, b.rowCount("workout_sessions"))
    }
}
