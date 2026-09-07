package com.example.data.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * O pull: validação, aplicação transacional, cursor e supressão de eco (T16.6).
 *
 * As duas regras que estes testes protegem:
 *
 * 1. **o cursor só avança depois do apply.** Gravá-lo antes transformaria uma falha de escrita em
 *    alteração remota perdida — e perder não tem conserto, enquanto reaplicar tem;
 * 2. **dado local sujo não é sobrescrito.** Aplicar o remoto por cima de uma alteração pendente é
 *    exatamente a perda silenciosa que a T16.7 existe para resolver.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncPullApplyTest {

    private val ownerUid = "uid-da-conta-a"

    private lateinit var server: FakeSparkSyncServer
    private lateinit var deviceA: SyncDevice
    private lateinit var deviceB: SyncDevice

    @Before
    fun setUp() = runTest {
        server = FakeSparkSyncServer()
        deviceA = SyncDevice(server, ownerUid, "device-a")
        deviceB = SyncDevice(server, ownerUid, "device-b")
        deviceA.bind()
        deviceB.bind()
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    // ------------------------------------------------------------------ clean

    @Test
    fun localLimpoRecebeORemoto() = runTest {
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals(1, outcome.applied)
        assertEquals("Treino A", deviceB.templateName(templateSyncId))
        assertEquals(1L, deviceB.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertTrue(deviceB.cursor() > 0)
        // Aplicar o que veio do servidor **não** produz mutação nova: sem isso, o laço não fecha.
        assertEquals(0, deviceB.pendingCount())
    }

    @Test
    fun edicaoRemotaAtualizaOLocalLimpo() = runTest {
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()
        deviceB.sync()

        deviceA.renameTemplate(templateSyncId, "Treino A v2")
        deviceA.sync()
        deviceB.sync()

        assertEquals("Treino A v2", deviceB.templateName(templateSyncId))
        assertEquals(2L, deviceB.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(0, deviceB.pendingCount())
    }

    // ------------------------------------------------------------------ dirty

    @Test
    fun localSujoNaoEsobrescritoPeloRemoto() = runTest {
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()
        deviceB.sync()

        // B edita localmente e **não** sincroniza; A edita e sincroniza.
        deviceB.renameTemplate(templateSyncId, "Versão do B")
        deviceA.renameTemplate(templateSyncId, "Versão do A")
        deviceA.sync()

        // B faz pull: o remoto está adiante e o local está sujo.
        deviceB.api.offline = true
        // (o push de B falharia por rede; o que importa aqui é o pull, então volta a ficar online)
        deviceB.api.offline = false
        val outcome = deviceB.sync() as SyncOutcome.Success

        // O treino local continua como o usuário o deixou.
        assertEquals("Versão do B", deviceB.templateName(templateSyncId))
        assertEquals(1, outcome.conflicts)

        val conflict = deviceB.conflicts().single()
        assertEquals(templateSyncId, conflict.entitySyncId)
        // Os dois lados ficam guardados: o local no Room e na Outbox, o remoto aqui.
        assertNotNull(conflict.remotePayload)
        assertTrue(conflict.remotePayload!!.contains("Versão do A"))
    }

    @Test
    fun mesmoConteudoDosDoisLadosConvergeSemConflito() = runTest {
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()
        deviceB.sync()

        // Os dois renomeiam para exatamente a mesma coisa. Não há o que decidir.
        deviceA.renameTemplate(templateSyncId, "Mesmo nome")
        deviceB.renameTemplate(templateSyncId, "Mesmo nome")
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals(0, outcome.conflicts)
        assertEquals(emptyList<SyncConflictEntity>(), deviceB.conflicts())
        // A pendência de B é cumprida pelo remoto idêntico, sem gastar revision nova.
        assertEquals(0, deviceB.pendingCount())
        assertEquals(2L, deviceB.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        assertEquals(2L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
    }

    // ------------------------------------------------------------------ eco

    @Test
    fun oProprioAparelhoNaoEntraEmLacoAoReceberOQueEnviou() = runTest {
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()

        val before = server.changeCount
        // Vários ciclos seguidos: o change log do próprio aparelho volta em todos eles até o
        // cursor passar dele.
        deviceA.sync()
        deviceA.sync()

        assertEquals("nenhuma mudança nova foi criada", before, server.changeCount)
        assertEquals(0, deviceA.pendingCount())
        assertEquals(0, deviceA.blockedCount())
        assertEquals("Treino A", deviceA.templateName(templateSyncId))
    }

    // ------------------------------------------------------------------ cursor

    @Test
    fun oCursorNaoAvancaQuandoAPaginaNaoPodeSerLida() = runTest {
        deviceA.newTemplate("Treino A")
        deviceA.sync()

        val cursorBefore = deviceB.cursor()
        deviceB.api.corruptNextPullPage = true
        val outcome = deviceB.sync() as SyncOutcome.Success

        // Uma mudança que este app não sabe ler pausa o sync **antes** dela.
        assertTrue(outcome.pausedAt is SyncApplyStop.Unsupported)
        assertEquals(cursorBefore, deviceB.cursor())
        assertNull(deviceB.templateName("qualquer"))
        assertEquals(0, outcome.applied)
    }

    @Test
    fun aPaginaVoltaAserAplicadaDepoisDeUmaFalha() = runTest {
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.sync()

        deviceB.api.corruptNextPullPage = true
        deviceB.sync()
        assertEquals(0L, deviceB.cursor())

        // O ciclo seguinte pede a mesma página e a aplica: reaplicar é idempotente.
        val outcome = deviceB.sync() as SyncOutcome.Success
        assertEquals(1, outcome.applied)
        assertEquals("Treino A", deviceB.templateName(templateSyncId))
        assertTrue(deviceB.cursor() > 0)
    }

    @Test
    fun referenciaQueNaoResolveNaoAvancaOCursorENaoEscreveNada() = runTest {
        // A cria um treino com um exercício de catálogo que B **não** tem instalado.
        val exerciseId = deviceA.installCanonicalExercise("canonical.supino", "Supino Reto")
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.addExerciseToTemplate(templateSyncId, exerciseId)
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        // Sem *fuzzy matching*: o app não escolhe "um exercício parecido". Ele para.
        assertTrue(outcome.pausedAt is SyncApplyStop.Failed)
        assertEquals(0L, deviceB.cursor())
        assertNull(deviceB.templateName(templateSyncId))

        // Com o catálogo instalado, a mesma página aplica.
        deviceB.installCanonicalExercise("canonical.supino", "Supino Reto")
        val second = deviceB.sync() as SyncOutcome.Success
        assertEquals(1, second.applied)
        assertEquals(listOf("canonical.supino"), deviceB.templateExerciseIds(templateSyncId))
    }

    // ------------------------------------------------------------------ histórico imutável

    @Test
    fun sessaoConcluidaChegaSemDisparEfeitoColateral() = runTest {
        val exerciseId = deviceA.installCanonicalExercise("canonical.supino", "Supino Reto")
        deviceB.installCanonicalExercise("canonical.supino", "Supino Reto")
        val sessionSyncId = deviceA.completeSession(exerciseId)
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals(1, outcome.applied)
        assertEquals(1, deviceB.rowCount("workout_sessions"))
        assertEquals(1, deviceB.rowCount("set_logs"))
        assertEquals(1L, deviceB.revisionOf(SyncEntityType.WORKOUT_SESSION, sessionSyncId))

        // Zero XP, zero conquista, zero evento, zero recorde: receber histórico não é executá-lo.
        assertEquals(0, deviceB.rowCount("xp_transactions"))
        assertEquals(0, deviceB.rowCount("gamification_events"))
        assertEquals(0, deviceB.rowCount("achievement_unlocks"))
        assertEquals(0, deviceB.rowCount("personal_records"))
        // E nenhuma mutação nova: o histórico não volta para o servidor.
        assertEquals(0, deviceB.pendingCount())
    }

    @Test
    fun sessaoConcluidaJaExistenteNaoEreescrita() = runTest {
        val exerciseId = deviceA.installCanonicalExercise("canonical.supino", "Supino Reto")
        deviceB.installCanonicalExercise("canonical.supino", "Supino Reto")
        val sessionSyncId = deviceA.completeSession(exerciseId, weight = 100f)
        deviceA.sync()
        deviceB.sync()

        // A mesma sessão chega de novo (o cursor de B é rebobinado, como aconteceria depois de um
        // erro de escrita): nada é duplicado e nada é reescrito.
        deviceB.database.syncCursorDao().upsert(
            SyncCursorEntity(ownerUid = ownerUid, lastPulledServerSequence = 0, lastSyncedAt = 1)
        )
        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals(0, outcome.applied)
        assertEquals(1, deviceB.rowCount("workout_sessions"))
        assertEquals(1, deviceB.rowCount("set_logs"))
        assertEquals(0, deviceB.rowCount("xp_transactions"))
        assertEquals(1L, deviceB.revisionOf(SyncEntityType.WORKOUT_SESSION, sessionSyncId))
    }

    // ------------------------------------------------------------------ ordenação

    @Test
    fun reordenacaoDeExerciciosConverge() = runTest {
        val supino = deviceA.installCanonicalExercise("canonical.supino", "Supino")
        val crucifixo = deviceA.installCanonicalExercise("canonical.crucifixo", "Crucifixo")
        deviceB.installCanonicalExercise("canonical.supino", "Supino")
        deviceB.installCanonicalExercise("canonical.crucifixo", "Crucifixo")

        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.addExerciseToTemplate(templateSyncId, supino, order = 0)
        deviceA.addExerciseToTemplate(templateSyncId, crucifixo, order = 1)
        deviceA.sync()
        deviceB.sync()
        assertEquals(
            listOf("canonical.supino", "canonical.crucifixo"),
            deviceB.templateExerciseIds(templateSyncId)
        )

        deviceA.reorderTemplateExercises(templateSyncId)
        deviceA.sync()
        deviceB.sync()

        // A ordem é dado de domínio (`position`), e atravessa dois aparelhos preservada.
        assertEquals(
            listOf("canonical.crucifixo", "canonical.supino"),
            deviceB.templateExerciseIds(templateSyncId)
        )
    }

    // ------------------------------------------------------------------ dependências

    @Test
    fun exercicioPersonalizadoChegaAntesDoTreinoQueOreferencia() = runTest {
        val customSyncId = deviceA.newCustomExercise("Rosca do vizinho")
        val exerciseLocalId = deviceA.database.workoutDao().getExerciseBySyncId(customSyncId)!!.id
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.addExerciseToTemplate(templateSyncId, exerciseLocalId)
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        // Os dois agregados chegam na mesma página, e a ordem de dependência é resolvida na
        // aplicação — não pela ordem incidental do JSON.
        assertEquals(2, outcome.applied)
        assertNotNull(deviceB.database.workoutDao().getExerciseBySyncId(customSyncId))
        assertEquals(listOf(customSyncId), deviceB.templateExerciseIds(templateSyncId))
    }

    // ------------------------------------------------------------------ sessão em andamento

    @Test
    fun mudancaNoTreinoEmExecucaoEadiadaEmVezDeAplicada() = runTest {
        val supino = deviceA.installCanonicalExercise("canonical.supino", "Supino")
        deviceB.installCanonicalExercise("canonical.supino", "Supino")
        val templateSyncId = deviceA.newTemplate("Treino A")
        deviceA.addExerciseToTemplate(templateSyncId, supino)
        deviceA.sync()
        deviceB.sync()

        // B começa a treinar **este** treino.
        deviceB.startWorkout(templateSyncId)

        // A muda o treino e sincroniza.
        deviceA.renameTemplate(templateSyncId, "Treino A reformulado")
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        // A mudança não entra no meio da execução, e não se perde: o cursor para antes dela.
        assertTrue(outcome.pausedAt is SyncApplyStop.DeferredActiveWorkout)
        assertEquals(0, outcome.applied)
        assertEquals("Treino A", deviceB.templateName(templateSyncId))

        // Terminado o treino, o ciclo seguinte aplica normalmente.
        deviceB.finishWorkout()
        val second = deviceB.sync() as SyncOutcome.Success
        assertEquals(1, second.applied)
        assertEquals("Treino A reformulado", deviceB.templateName(templateSyncId))
    }

    @Test
    fun outroTreinoContinuaChegandoDuranteUmaExecucao() = runTest {
        val emExecucao = deviceA.newTemplate("Em execução")
        deviceA.sync()
        deviceB.sync()
        deviceB.startWorkout(emExecucao)

        // Uma medida e um treino diferente não têm nada a ver com a execução em andamento.
        val medida = deviceA.newMeasurement(weightKg = 82f)
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals(1, outcome.applied)
        assertEquals(82f, deviceB.measurementWeight(medida))
    }

    // ------------------------------------------------------------------ isolamento de conta

    @Test
    fun umaContaNuncaRecebeMudancaDeOutra() = runTest {
        val outraConta = SyncDevice(server, "uid-da-conta-b", "device-c")
        try {
            outraConta.bind()
            outraConta.newTemplate("Treino secreto da conta B")
            outraConta.sync()

            deviceA.newTemplate("Treino da conta A")
            deviceA.sync()
            deviceB.sync()

            // B (mesma conta que A) recebe só o treino de A.
            assertEquals(
                listOf("Treino da conta A"),
                deviceB.database.workoutDao().getAllTemplatesSync().map { it.name }
            )
        } finally {
            outraConta.close()
        }
    }
}
