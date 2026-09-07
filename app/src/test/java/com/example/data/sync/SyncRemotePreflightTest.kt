package com.example.data.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Usar a versão da nuvem" confirma o estado remoto antes de sobrescrever o local (T16.7.1).
 *
 * ```text
 * conflito guarda remoteRevision = 2
 *        ↓
 * outro aparelho escreve   →   servidor vai para 3
 *        ↓
 * usuário toca "Usar versão da nuvem"
 *        ↓
 * NÃO aplica a revision 2                ← o defeito que esta suíte existe para impedir
 * conflito atualizado para a 3, PENDING
 * ```
 *
 * O que se prova aqui é uma assimetria deliberada: **[SyncConflictChoice.USE_REMOTE] exige
 * confirmação remota porque sobrescreve dado local**; manter o local, excluir mesmo assim e
 * confirmar uma exclusão que a nuvem já fez continuam funcionando offline, porque nenhuma delas
 * grava conteúdo vindo do servidor.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncRemotePreflightTest {

    private val ownerUid = "uid-da-conta-a"
    private val outraConta = "uid-da-conta-b"

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

    /** A e B partem da mesma revision e editam sem sincronizar no meio. B fica com o conflito. */
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

    /** Um terceiro aparelho da mesma conta, que escreve enquanto a tela de conflito está aberta. */
    private suspend fun terceiroAparelho(block: suspend (SyncDevice) -> Unit) {
        val c = SyncDevice(server, ownerUid, "device-c")
        try {
            c.bind()
            c.sync()
            block(c)
            c.sync()
        } finally {
            c.close()
        }
    }

    private suspend fun conflitoDeB(): SyncConflictId =
        b.conflictSummaries().single().id

    // ------------------------------------------------------------------ caso 1 — nada mudou

    @Test
    fun remoteInalteradoPermiteAplicarACopiaDaNuvem() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val mudancasAntes = server.changeCount

        val resolution = b.resolve(conflitoDeB(), SyncConflictChoice.USE_REMOTE)

        assertEquals(SyncConflictResolution.Applied, resolution)
        assertEquals("Versão do A", b.templateName(templateSyncId))
        assertEquals(2L, b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(0, b.conflicts().size)
        // Aplicar o remoto continua gerando **zero** mutação: devolver ao servidor o que veio dele
        // seria um laço que não fecha.
        assertEquals(0, b.pendingCount())
        assertEquals(0, b.blockedCount())
        b.sync()
        assertEquals(mudancasAntes, server.changeCount)
    }

    @Test
    fun aConfirmacaoENecessariaEAcontece() = runTest {
        conflitoDeEdicaoConcorrente()
        val antes = b.api.entityStateCalls

        b.resolve(conflitoDeB(), SyncConflictChoice.USE_REMOTE)

        // Uma consulta, e uma só: a resolução pergunta o estado atual antes de escrever.
        assertEquals(antes + 1, b.api.entityStateCalls)
    }

    // ------------------------------------------------------------------ caso 2 — remote avançou

    @Test
    fun remoteAvancadoNaoAplicaARevisionAntigaEExigeNovaDecisao() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()
        assertEquals(2L, b.conflicts().single().remoteRevision)

        terceiroAparelho { it.renameTemplate(templateSyncId, "Versão do C") }
        assertEquals(3L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))

        val resolution = b.resolve(conflito, SyncConflictChoice.USE_REMOTE)

        assertEquals(SyncConflictResolution.RemoteChanged, resolution)
        // A revision 2 — que o servidor já sabe estar superada — **não** foi aplicada.
        assertEquals("Versão do B", b.templateName(templateSyncId))
        // A revision conhecida continua sendo a que B aprendeu no último sync bem-sucedido (1).
        // Gravar a 2 aqui — sem escrever o conteúdo dela — faria o aparelho mentir sobre o que tem.
        assertEquals(
            "uma resolução recusada não pode atualizar a revision conhecida",
            1L,
            b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId)
        )

        // O conflito continua existindo, agora descrevendo o estado atual da nuvem.
        val atualizado = b.conflicts().single()
        assertEquals(3L, atualizado.remoteRevision)
        assertEquals(SyncConflictStatus.PENDING.name, atualizado.status)
        // E a alteração local continua guardada dos dois lados.
        assertEquals(1, b.blockedCount())
    }

    @Test
    fun depoisDoRemoteAvancarATelaMostraAVersaoNova() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()

        terceiroAparelho { it.renameTemplate(templateSyncId, "Versão do C") }
        b.resolve(conflito, SyncConflictChoice.USE_REMOTE)

        val summary = b.conflictSummaries().single()
        val nome = summary.differences.single { it.label == "Nome" }
        assertEquals("Versão do B", nome.local)
        // A escolha anterior não vale mais: o usuário revê **este** conteúdo antes de decidir.
        assertEquals("Versão do C", nome.remote)
        assertTrue(SyncConflictChoice.USE_REMOTE in summary.choices)
        assertFalse("a decisão anterior não pode ficar 'aguardando envio'", summary.awaitingPush)
    }

    @Test
    fun escolherDeNovoDepoisDaAtualizacaoConvergeComOServidor() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        terceiroAparelho { it.renameTemplate(templateSyncId, "Versão do C") }

        // Primeira tentativa: recusada, porque a nuvem mudou.
        assertEquals(
            SyncConflictResolution.RemoteChanged,
            b.resolve(conflitoDeB(), SyncConflictChoice.USE_REMOTE)
        )
        // Segunda: agora a decisão é sobre a revision 3, que é a atual.
        assertEquals(
            SyncConflictResolution.Applied,
            b.resolve(conflitoDeB(), SyncConflictChoice.USE_REMOTE)
        )

        assertEquals("Versão do C", b.templateName(templateSyncId))
        assertEquals(3L, b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(0, b.conflicts().size)
        assertEquals(0, b.pendingCount())

        b.sync()
        assertEquals(
            "Versão do C",
            payloadField(
                server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId),
                "name"
            )
        )
        assertEquals(0, b.pendingCount())
    }

    // ------------------------------------------------------------------ caso 3 — virou tombstone

    @Test
    fun remoteQueViroUExclusaoNaoAplicaOConteudoAntigo() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()

        terceiroAparelho { it.deleteTemplate(templateSyncId) }
        assertTrue(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))

        val resolution = b.resolve(conflito, SyncConflictChoice.USE_REMOTE)

        assertEquals(SyncConflictResolution.RemoteChanged, resolution)
        // O conteúdo da revision 2 **não** foi escrito, e o local continua onde estava.
        assertEquals("Versão do B", b.templateName(templateSyncId))

        // O conflito passou a ser o que ele de fato é: um item excluído na nuvem.
        val atualizado = b.conflicts().single()
        assertEquals(SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED.name, atualizado.kind)
        assertEquals(3L, atualizado.remoteRevision)

        val summary = b.conflictSummaries().single()
        assertEquals(SyncConflictCategory.DELETED_ELSEWHERE, summary.category)
        assertEquals(
            listOf(
                SyncConflictChoice.CONFIRM_REMOTE_DELETE,
                SyncConflictChoice.KEEP_LOCAL_AS_NEW
            ),
            summary.choices
        )
        // "Usar versão da nuvem" deixa de ser oferecida: a nuvem não tem mais este item.
        assertFalse(SyncConflictChoice.USE_REMOTE in summary.choices)
    }

    @Test
    fun manterComoItemNovoDepoisDaExclusaoUsaIdentidadeNova() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        terceiroAparelho { it.deleteTemplate(templateSyncId) }
        b.resolve(conflitoDeB(), SyncConflictChoice.USE_REMOTE)

        assertEquals(
            SyncConflictResolution.Queued,
            b.resolve(conflitoDeB(), SyncConflictChoice.KEEP_LOCAL_AS_NEW)
        )

        // A identidade morta continua morta; o conteúdo volta como entidade nova.
        assertFalse(b.templateExists(templateSyncId))
        val nova = b.database.workoutDao().getAllTemplatesSync().single { it.name == "Versão do B" }
        assertNotEquals(templateSyncId, nova.syncId)

        b.sync()
        assertTrue(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertFalse(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, nova.syncId))
    }

    // ------------------------------------------------------------------ caso 4 — sem rede

    @Test
    fun semRedeNadaEAplicadoENadaEPerdido() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()
        val conflitoAntes = b.conflicts().single()

        b.api.offline = true
        val resolution = b.resolve(conflito, SyncConflictChoice.USE_REMOTE)

        assertEquals(SyncConflictResolution.RemoteUnavailable, resolution)
        // O snapshot guardado **não** é aplicado como plano B — que é o ponto inteiro da correção.
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(1, b.blockedCount())
        assertEquals(conflitoAntes, b.conflicts().single())
    }

    @Test
    fun servidorIndisponivelNaoAplicaOSnapshotGuardado() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()

        b.api.isConfigured = false
        assertEquals(
            SyncConflictResolution.RemoteUnavailable,
            b.resolve(conflito, SyncConflictChoice.USE_REMOTE)
        )
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(1, b.conflicts().size)
    }

    // ------------------------------------------------------------------ caso 5 — troca de conta

    @Test
    fun trocaDeContaDuranteAConsultaImpedeAAplicacao() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()

        // O usuário sai e entra com outra conta **enquanto** a requisição está no ar. A resposta
        // chega depois disso, e a revalidação acontece depois dela.
        b.api.onEntityStateResponse = { b.sessionUid = outraConta }

        val resolution = b.resolve(conflito, SyncConflictChoice.USE_REMOTE)

        assertEquals(SyncConflictResolution.AccountMismatch, resolution)
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(1, b.conflicts().size)
        assertEquals(1, b.blockedCount())
    }

    @Test
    fun respostaAutenticadaComOutraContaNuncaEntraNoDatasetDestaConta() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()

        // A conta B tem um treino com a **mesma** identidade global — dois aparelhos que
        // restauraram o mesmo backup em contas diferentes.
        val outroAparelho = SyncDevice(server, outraConta, "device-de-outra-conta")
        try {
            outroAparelho.bind()
            outroAparelho.restoreTemplate(templateSyncId, "Treino da conta B")
            outroAparelho.renameTemplate(templateSyncId, "Treino da conta B")
            outroAparelho.sync()
        } finally {
            outroAparelho.close()
        }

        // A requisição sai autenticada como a outra conta: o servidor responde o dado **dela**.
        b.api.authenticatedUid = outraConta
        val resolution = b.resolve(conflito, SyncConflictChoice.USE_REMOTE)

        assertEquals(SyncConflictResolution.AccountMismatch, resolution)
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(1, b.conflicts().size)
    }

    // ------------------------------------------------------------------ caso 6 — toque duplo

    @Test
    fun toqueDuploProduzUmaConsultaRemotaEUmaAplicacao() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()
        val antes = b.api.entityStateCalls
        val mudancasAntes = server.changeCount

        val resultados = listOf(
            async { b.resolve(conflito, SyncConflictChoice.USE_REMOTE) },
            async { b.resolve(conflito, SyncConflictChoice.USE_REMOTE) }
        ).awaitAll()

        // Uma operação lógica: uma consulta remota, uma aplicação, e o segundo toque encontrando a
        // decisão já tomada.
        assertEquals(antes + 1, b.api.entityStateCalls)
        assertTrue(SyncConflictResolution.Applied in resultados)
        assertTrue(resultados.any { it != SyncConflictResolution.Applied })
        assertEquals("Versão do A", b.templateName(templateSyncId))
        assertEquals(0, b.conflicts().size)
        assertEquals(0, b.pendingCount())
        assertEquals(mudancasAntes, server.changeCount)
    }

    // ------------------------------------------------------------------ caso 7 — offline-first

    @Test
    fun manterOLocalContinuaFuncionandoSemRede() = runTest {
        val templateSyncId = conflitoDeEdicaoConcorrente()
        val conflito = conflitoDeB()
        val consultasAntes = b.api.entityStateCalls

        b.api.offline = true
        val resolution = b.resolve(conflito, SyncConflictChoice.KEEP_LOCAL)

        assertEquals(SyncConflictResolution.Queued, resolution)
        // A correção do "usar remoto" **não** pode ter tornado esta decisão dependente de rede.
        assertEquals(
            "manter o local não consulta o servidor",
            consultasAntes,
            b.api.entityStateCalls
        )
        assertEquals(1, b.database.syncOutboxDao().pendingFor(ownerUid).size)
        assertEquals(2L, b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(
            SyncConflictStatus.AWAITING_PUSH.name,
            b.conflicts().single().status
        )

        // E a decisão sobrevive: quando a rede volta, ela sobe pelo caminho normal.
        b.api.offline = false
        b.sync()
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(0, b.conflicts().size)
    }

    @Test
    fun confirmarExclusaoDaNuvemContinuaFuncionandoSemRede() = runTest {
        // Um tombstone não volta a ser entidade viva: `deleted = 1` é terminal no servidor. Por
        // isso confirmar a exclusão continua correto em qualquer revision futura — e não precisa
        // de rede para ser provado.
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()
        b.renameTemplate(templateSyncId, "Editado por B")
        a.deleteTemplate(templateSyncId)
        a.sync()
        b.sync()

        val consultasAntes = b.api.entityStateCalls
        b.api.offline = true

        assertEquals(
            SyncConflictResolution.Applied,
            b.resolve(conflitoDeB(), SyncConflictChoice.CONFIRM_REMOTE_DELETE)
        )
        assertEquals(consultasAntes, b.api.entityStateCalls)
        assertFalse(b.templateExists(templateSyncId))
        assertEquals(0, b.conflicts().size)
        assertEquals(0, b.pendingCount())
    }

    // ------------------------------------------------------------------ estados impossíveis
    //
    // O protocolo diz que estes não acontecem. São exatamente os que não dá para montar num teste
    // de ponta a ponta — e exatamente os que, se acontecessem, custariam dado do usuário. A regra
    // é pura de propósito para poder ser exercitada direto.

    private fun conflito(
        kind: SyncConflictKind,
        remoteRevision: Long? = 5,
        remotePayloadHash: String? = "hash-remoto"
    ) = SyncConflictEntity(
        ownerUid = ownerUid,
        entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
        entitySyncId = "sync-id",
        kind = kind.name,
        remoteRevision = remoteRevision,
        remotePayloadHash = remotePayloadHash,
        detectedAt = 0
    )

    private fun estado(
        serverRevision: Long = 5,
        deleted: Boolean = false,
        payloadHash: String? = "hash-remoto",
        entitySyncId: String = "sync-id",
        entityType: String = SyncEntityType.WORKOUT_TEMPLATE.name
    ) = SyncEntityStateDto(
        ownerUid = ownerUid,
        entityType = entityType,
        entitySyncId = entitySyncId,
        entitySchemaVersion = 1,
        serverRevision = serverRevision,
        deleted = deleted,
        payloadHash = payloadHash
    )

    @Test
    fun tombstoneQueVoltaVivoEViolacaoDeIntegridade() {
        // `deleted = 1` é terminal no servidor, garantido pelo banco. Se o servidor disser o
        // contrário, aceitar em silêncio seria participar de uma ressurreição.
        val verdict = SyncRemotePreflight.evaluate(
            conflito(SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED),
            SyncEntityType.WORKOUT_TEMPLATE,
            estado(serverRevision = 6, deleted = false)
        )

        assertEquals(SyncRemotePreflightVerdict.Resurrected, verdict)
    }

    @Test
    fun respostaSobreOutraIdentidadeERecusada() {
        assertEquals(
            SyncRemotePreflightVerdict.Mismatched,
            SyncRemotePreflight.evaluate(
                conflito(SyncConflictKind.STALE_LOCAL_CHANGE),
                SyncEntityType.WORKOUT_TEMPLATE,
                estado(entitySyncId = "outra-identidade")
            )
        )
        assertEquals(
            SyncRemotePreflightVerdict.Mismatched,
            SyncRemotePreflight.evaluate(
                conflito(SyncConflictKind.STALE_LOCAL_CHANGE),
                SyncEntityType.WORKOUT_TEMPLATE,
                estado(entityType = SyncEntityType.WORKOUT_PROGRAM.name)
            )
        )
    }

    @Test
    fun mesmaRevisionComConteudoOutroNaoEAplicada() {
        // Impossível pelo protocolo — e, sendo impossível, não é algo sobre o que valha a pena
        // adivinhar. O conflito é atualizado e o usuário revê.
        assertEquals(
            SyncRemotePreflightVerdict.Changed,
            SyncRemotePreflight.evaluate(
                conflito(SyncConflictKind.STALE_LOCAL_CHANGE),
                SyncEntityType.WORKOUT_TEMPLATE,
                estado(payloadHash = "outro-hash")
            )
        )
    }

    @Test
    fun revisionRemotaDesconhecidaNuncaEConsideradaAtual() {
        // Sem revision guardada não há como afirmar que a cópia é a atual — e "não dá para
        // afirmar" nunca pode virar "aplica".
        assertEquals(
            SyncRemotePreflightVerdict.Changed,
            SyncRemotePreflight.evaluate(
                conflito(SyncConflictKind.STALE_LOCAL_CHANGE, remoteRevision = null),
                SyncEntityType.WORKOUT_TEMPLATE,
                estado()
            )
        )
    }

    @Test
    fun excluirMesmoAssimContinuaFuncionandoSemRede() = runTest {
        val measurementSyncId = a.newMeasurement(80f)
        a.sync()
        b.sync()

        b.deleteMeasurement(measurementSyncId)
        a.database.bodyMeasurementDao().getMeasurementBySyncId(measurementSyncId)!!.let {
            a.measurements.updateMeasurement(it.copy(weightKg = 82f))
        }
        a.sync()
        b.sync()

        val consultasAntes = b.api.entityStateCalls
        b.api.offline = true

        assertEquals(
            SyncConflictResolution.Queued,
            b.resolve(conflitoDeB(), SyncConflictChoice.CONFIRM_LOCAL_DELETE)
        )
        assertEquals(consultasAntes, b.api.entityStateCalls)
        assertEquals(1, b.database.syncOutboxDao().pendingFor(ownerUid).size)
    }
}
