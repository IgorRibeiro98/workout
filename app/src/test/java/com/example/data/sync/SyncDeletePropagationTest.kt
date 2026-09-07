package com.example.data.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Propagação de exclusão, tombstone e prevenção de ressurreição, entre aparelhos (T16.7).
 *
 * ```text
 * A deleta X  →  tombstone  →  B recebe  →  X some em B
 * B offline com X            →  B volta   →  X **não** ressuscita
 * ```
 *
 * Dois bancos Room de verdade, dois `deviceId`, um change log — a mesma montagem da T16.6, agora
 * com exclusão no meio.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncDeletePropagationTest {

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

    // ------------------------------------------------------------------ propagação

    @Test
    fun exclusaoEmUmAparelhoRemoveNoOutro() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()
        assertTrue(b.templateExists(templateSyncId))

        a.deleteTemplate(templateSyncId)
        a.sync()
        b.sync()

        assertFalse("o treino precisa sumir no outro aparelho", b.templateExists(templateSyncId))
    }

    @Test
    fun exclusaoRemotaNaoGeraMutacaoDeSaida() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        a.deleteTemplate(templateSyncId)
        a.sync()
        val changesAntes = server.changeCount

        b.sync()

        // B apagou porque o servidor mandou. Se isso virasse `DELETE` na Outbox, B devolveria ao
        // servidor a exclusão que acabou de receber dele — e o log cresceria para sempre.
        assertEquals(0, b.pendingCount())
        assertEquals(0, b.blockedCount())
        assertEquals(changesAntes, server.changeCount)
    }

    @Test
    fun exclusaoFeitaOfflineSobeQuandoARedeVolta() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        a.api.offline = true
        a.deleteTemplate(templateSyncId)
        assertEquals(SyncOutcome.Offline, a.sync())
        // O app respondeu localmente: o treino já sumiu daqui, e a intenção está guardada.
        assertFalse(a.templateExists(templateSyncId))
        assertEquals(1, a.pendingCount())

        a.api.offline = false
        a.sync()
        b.sync()

        assertTrue(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertFalse(b.templateExists(templateSyncId))
    }

    @Test
    fun medidaExcluidaSomeNoOutroAparelho() = runTest {
        val measurementSyncId = a.newMeasurement(weightKg = 80.5f)
        a.sync()
        b.sync()
        assertTrue(b.measurementExists(measurementSyncId))

        a.deleteMeasurement(measurementSyncId)
        a.sync()
        b.sync()

        assertFalse(b.measurementExists(measurementSyncId))
    }

    // ------------------------------------------------------------------ ressurreição

    @Test
    fun aparelhoOfflineNaoRessuscitaOQueFoiExcluido() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        // B fica offline com a cópia antiga e ainda a edita.
        b.api.offline = true
        b.renameTemplate(templateSyncId, "Editado offline por B")

        // A apaga e sincroniza.
        a.deleteTemplate(templateSyncId)
        a.sync()

        // B volta.
        b.api.offline = false
        val outcome = b.sync() as SyncOutcome.Success

        // O treino **não** voltou no servidor.
        assertTrue(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(2L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        // E o trabalho de B não foi jogado fora: ele virou conflito, com as duas intenções vivas.
        assertEquals(1, outcome.conflicts)
        val conflict = b.conflicts().single()
        assertEquals(SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED.name, conflict.kind)
        assertTrue("a alteração local continua guardada", b.templateExists(templateSyncId))
        assertEquals(1, b.blockedCount())
    }

    @Test
    fun exclusaoRemotaNaoApagaAlteracaoLocalPendente() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        // B edita e ainda não enviou; A apaga e envia.
        b.renameTemplate(templateSyncId, "Versão de B")
        a.deleteTemplate(templateSyncId)
        a.sync()

        // O pull de B traz o tombstone. Ele **não** pode apagar o que B fez.
        val outcome = b.sync() as SyncOutcome.Success

        assertEquals(1, outcome.conflicts)
        assertTrue(b.templateExists(templateSyncId))
        assertEquals("Versão de B", b.templateName(templateSyncId))
        assertEquals(
            SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED.name,
            b.conflicts().single().kind
        )
    }

    @Test
    fun exclusaoLocalContraAtualizacaoRemotaViraConflito() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        // B atualiza primeiro e ganha a revision seguinte.
        b.renameTemplate(templateSyncId, "Versão de B")
        b.sync()

        // A apaga com base na revision antiga: quem chegou primeiro definiu a ordem.
        a.deleteTemplate(templateSyncId)
        val outcome = a.sync() as SyncOutcome.Success

        assertEquals(1, outcome.conflicts)
        assertEquals(
            SyncConflictKind.LOCAL_DELETED_REMOTE_MODIFIED.name,
            a.conflicts().single().kind
        )
        // Nada foi apagado no servidor: a edição de B continua lá.
        assertFalse(server.isDeleted(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(
            "Versão de B",
            payloadField(server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId), "name")
        )
    }

    @Test
    fun osDoisAparelhosApagandoOMesmoItemConvergemSemConflito() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        b.api.offline = true
        b.deleteTemplate(templateSyncId)
        a.deleteTemplate(templateSyncId)
        a.sync()

        b.api.offline = false
        val outcome = b.sync() as SyncOutcome.Success

        // Os dois queriam a mesma coisa. Isso não é divergência.
        assertEquals(0, outcome.conflicts)
        assertEquals(0, b.pendingCount())
        assertEquals(0, b.blockedCount())
        assertFalse(b.templateExists(templateSyncId))
    }

    // ------------------------------------------------------------------ histórico e execução

    @Test
    fun exclusaoDeTemplateNaoAlteraSessaoConcluida() = runTest {
        val exerciseId = a.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        b.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        val templateSyncId = a.newTemplate("Treino A")
        val sessionSyncId = a.completeSession(exerciseId)
        a.sync()
        b.sync()

        val seriesAntes = b.rowCount("set_logs")
        assertEquals(1, b.rowCount("workout_sessions"))

        a.deleteTemplate(templateSyncId)
        a.sync()
        b.sync()

        // O plano some; o que aconteceu continua registrado, com as séries intactas.
        assertFalse(b.templateExists(templateSyncId))
        assertEquals(1, b.rowCount("workout_sessions"))
        assertEquals(seriesAntes, b.rowCount("set_logs"))
        assertNotNull(b.database.workoutDao().getSessionIdBySyncId(sessionSyncId))
    }

    @Test
    fun exclusaoRemotaDoTemplateEmExecucaoEAdiada() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        // B começa a treinar esse template; A apaga no meio.
        b.startWorkout(templateSyncId)
        a.deleteTemplate(templateSyncId)
        a.sync()

        val durante = b.sync() as SyncOutcome.Success

        // A execução não é destruída por baixo do usuário: a exclusão espera.
        assertTrue(durante.pausedAt is SyncApplyStop.DeferredActiveWorkout)
        assertTrue(b.templateExists(templateSyncId))

        b.finishWorkout()
        b.sync()

        // Terminado o treino, a exclusão aplica normalmente — e a sessão continua lá.
        assertFalse(b.templateExists(templateSyncId))
        assertEquals(1, b.rowCount("workout_sessions"))
    }

    @Test
    fun exclusaoDeSessaoConcluidaPropaga() = runTest {
        val exerciseId = a.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        b.installCanonicalExercise("supino-reto-barra", "Supino Reto")
        val sessionSyncId = a.completeSession(exerciseId)
        a.sync()
        b.sync()
        assertEquals(1, b.rowCount("workout_sessions"))

        // Apagar o próprio histórico é direito do usuário — e apagar não é reescrever.
        val session = a.database.workoutDao().getSessionWithDetailsBySyncId(sessionSyncId)!!.session
        a.mutations.mutate {
            a.database.workoutDao().deleteWorkoutSession(session)
            delete(SyncEntityType.WORKOUT_SESSION, sessionSyncId)
        }
        a.sync()
        b.sync()

        assertEquals(0, b.rowCount("workout_sessions"))
        // O cascade do schema levou os filhos junto, dos dois lados.
        assertEquals(0, b.rowCount("set_logs"))
        // E nenhum efeito colateral de gamificação foi disparado por receber a exclusão.
        assertEquals(0, b.rowCount("xp_transactions"))
    }

    // ------------------------------------------------------------------ offline longo

    @Test
    fun aparelhoMuitoTempoOfflineRecebeTudoNaOrdem() = runTest {
        val primeiro = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        // B some por um longo período. A trabalha.
        b.api.offline = true
        a.renameTemplate(primeiro, "Treino A revisado")
        a.sync()
        a.deleteTemplate(primeiro)
        a.sync()
        val segundo = a.newTemplate("Treino B")
        a.sync()
        a.renameTemplate(segundo, "Treino B revisado")
        a.sync()

        b.api.offline = false
        b.sync()

        // Estado final correto: o primeiro sumiu, o segundo chegou com o último nome.
        assertFalse(b.templateExists(primeiro))
        assertEquals("Treino B revisado", b.templateName(segundo))
        assertEquals(0, b.conflicts().size)
    }

    // ------------------------------------------------------------------ três aparelhos

    @Test
    fun tresAparelhosConvergemDepoisDeConflitoExclusaoERecriacao() = runTest {
        // O smoke local do §107, em um teste: A, B e C na mesma conta, com um conflito resolvido,
        // uma exclusão propagada e uma criação depois dela.
        val c = SyncDevice(server, ownerUid, "device-c")
        try {
            c.bind()

            val compartilhado = a.newTemplate("Treino comum")
            a.sync()
            b.sync()
            c.sync()

            // 1. A e B editam o mesmo treino sem sincronizar no meio.
            a.renameTemplate(compartilhado, "Versão do A")
            b.renameTemplate(compartilhado, "Versão do B")
            a.sync()
            b.sync()
            assertEquals(1, b.conflicts().size)

            // 2. B decide ficar com a versão dele. C recebe a decisão como uma mudança normal.
            val conflito = b.conflictSummaries().single()
            assertEquals(
                SyncConflictResolution.Queued,
                b.resolve(conflito.id, SyncConflictChoice.KEEP_LOCAL)
            )
            b.sync()
            a.sync()
            c.sync()
            assertEquals("Versão do B", a.templateName(compartilhado))
            assertEquals("Versão do B", c.templateName(compartilhado))

            // 3. A apaga enquanto B está offline.
            b.api.offline = true
            a.deleteTemplate(compartilhado)
            a.sync()
            c.sync()
            assertFalse(c.templateExists(compartilhado))

            // 4. C cria um treino depois da exclusão.
            val depois = c.newTemplate("Criado depois")
            c.sync()

            // 5. B volta: recebe a exclusão e a criação, na ordem, sem ressuscitar nada.
            b.api.offline = false
            b.sync()

            assertFalse("o treino apagado não pode voltar", b.templateExists(compartilhado))
            assertEquals("Criado depois", b.templateName(depois))
            assertEquals(0, b.conflicts().size)
            assertEquals(0, b.pendingCount())

            // E os três terminam iguais.
            a.sync()
            assertEquals("Criado depois", a.templateName(depois))
            assertFalse(a.templateExists(compartilhado))
        } finally {
            c.close()
        }
    }

    // ------------------------------------------------------------------ restore antigo

    @Test
    fun backupAntigoRestauradoPerdeDeNovoOQueFoiExcluidoDepois() = runTest {
        // O cenário do §99: existe X, tira-se um backup, X é excluído, e um aparelho restaura
        // aquele backup antigo. Se o sync não removesse X de novo, um restore desfaria em silêncio
        // uma exclusão que o usuário fez de propósito.
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        a.deleteTemplate(templateSyncId)
        a.sync()

        // B restaura o snapshot antigo: o treino volta a existir aqui, e o estado de sync é
        // zerado dentro do mesmo commit — exatamente o que a T16.5 faz.
        b.resetSyncStateAsRestoreDoes()
        if (!b.templateExists(templateSyncId)) b.restoreTemplate(templateSyncId, "Treino A")
        assertTrue(b.templateExists(templateSyncId))

        b.sync()

        // O change log é relido do começo, e o tombstone posterior chega junto: X sai de novo.
        assertFalse("o restore não pode desfazer uma exclusão", b.templateExists(templateSyncId))
        assertEquals(0, b.conflicts().size)
        assertEquals(0, b.pendingCount())
    }
}
