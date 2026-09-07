package com.example.data.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Convergência entre aparelhos da mesma Conta Spark (T16.6).
 *
 * ```text
 * DB do celular A ─┐
 * DB do celular B ─┼─▶ um change log, uma conta
 * DB do celular C ─┘
 * ```
 *
 * Este é o *smoke* local exigido pela tarefa: bancos Room **independentes**, um servidor com as
 * regras do protocolo, e nenhum atalho — cada aparelho escreve pelos repositórios de produção e
 * só conhece o outro pelo que o servidor entrega.
 *
 * O que ele prova é convergência no caminho feliz e **detecção sem perda** no caminho conflitante.
 * Resolver o conflito é da T16.7, e nenhum teste aqui finge o contrário.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncMultiDeviceTest {

    private val ownerUid = "uid-da-conta"

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

    @Test
    fun aEditaEBrecebe() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()

        b.sync()

        assertEquals("Treino A", b.templateName(templateSyncId))
        assertEquals(1L, b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
    }

    @Test
    fun bEditaEArecebe() = runTest {
        val templateSyncId = a.newTemplate("Treino A")
        a.sync()
        b.sync()

        b.renameTemplate(templateSyncId, "Treino do B")
        b.sync()
        a.sync()

        assertEquals("Treino do B", a.templateName(templateSyncId))
        assertEquals(2L, a.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        // Sem conflito: ninguém editou concorrentemente.
        assertEquals(emptyList<SyncConflictEntity>(), a.conflicts())
        assertEquals(emptyList<SyncConflictEntity>(), b.conflicts())
    }

    @Test
    fun idaEVoltaVariasVezesConverge() = runTest {
        val templateSyncId = a.newTemplate("v1")
        a.sync(); b.sync()

        b.renameTemplate(templateSyncId, "v2"); b.sync(); a.sync()
        a.renameTemplate(templateSyncId, "v3"); a.sync(); b.sync()
        b.renameTemplate(templateSyncId, "v4"); b.sync(); a.sync()

        assertEquals("v4", a.templateName(templateSyncId))
        assertEquals("v4", b.templateName(templateSyncId))
        assertEquals(
            a.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId),
            b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId)
        )
        assertEquals(0, a.pendingCount())
        assertEquals(0, b.pendingCount())
    }

    @Test
    fun tresAparelhosConvergem() = runTest {
        val c = SyncDevice(server, ownerUid, "device-c")
        try {
            c.bind()

            val templateSyncId = a.newTemplate("Treino comum")
            a.sync()
            b.sync()
            c.sync()
            assertEquals("Treino comum", c.templateName(templateSyncId))

            b.renameTemplate(templateSyncId, "Editado pelo B")
            b.sync()
            c.sync()
            a.sync()

            assertEquals("Editado pelo B", a.templateName(templateSyncId))
            assertEquals("Editado pelo B", c.templateName(templateSyncId))
            val revision = a.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId)
            assertEquals(revision, b.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
            assertEquals(revision, c.revisionOf(SyncEntityType.WORKOUT_TEMPLATE, templateSyncId))
        } finally {
            c.close()
        }
    }

    @Test
    fun edicaoConcorrenteNaoPerdeNenhumDosDoisLados() = runTest {
        val templateSyncId = a.newTemplate("Base")
        a.sync()
        b.sync()

        // Os dois partem da mesma revision e editam sem sincronizar no meio.
        a.renameTemplate(templateSyncId, "Versão do A")
        b.renameTemplate(templateSyncId, "Versão do B")

        a.sync()
        b.sync()

        // Nada foi perdido: o remoto tem o de A, o aparelho de B tem o de B, e o conflito guarda
        // os dois lados para a T16.7.
        assertEquals("Versão do A", a.templateName(templateSyncId))
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(
            "Versão do A",
            payloadField(server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId), "name")
        )

        val conflict = b.conflicts().single()
        assertEquals(templateSyncId, conflict.entitySyncId)
        assertEquals(SyncConflictKind.STALE_LOCAL_CHANGE.name, conflict.kind)
        assertTrue(conflict.remotePayload!!.contains("Versão do A"))
        // A alteração de B continua guardada, fora da fila de envio.
        assertEquals(1, b.blockedCount())
        assertEquals(0, b.pendingCount())

        // E o conflito de um agregado não trava os outros.
        val outroSyncId = b.newTemplate("Treino novo do B")
        b.sync()
        a.sync()
        assertEquals("Treino novo do B", a.templateName(outroSyncId))
    }

    @Test
    fun conflitoNaoSeResolveSozinhoEmCiclosSeguintes() = runTest {
        val templateSyncId = a.newTemplate("Base")
        a.sync(); b.sync()

        a.renameTemplate(templateSyncId, "Versão do A")
        b.renameTemplate(templateSyncId, "Versão do B")
        a.sync(); b.sync()

        // Dez ciclos depois, nada mudou de lado sozinho. Nenhum last-write-wins apareceu.
        repeat(10) { b.sync(); a.sync() }

        assertEquals("Versão do A", a.templateName(templateSyncId))
        assertEquals("Versão do B", b.templateName(templateSyncId))
        assertEquals(1, b.conflicts().size)
        assertEquals(
            "Versão do A",
            payloadField(server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, templateSyncId), "name")
        )
    }

    @Test
    fun offlineNoAeOnlineDepoisConvergeComB() = runTest {
        a.api.offline = true
        val t1 = a.newTemplate("Offline 1")
        val t2 = a.newTemplate("Offline 2")
        val medida = a.newMeasurement(weightKg = 77.5f)

        assertEquals(SyncOutcome.Offline, a.sync())
        assertEquals(3, a.pendingCount())
        // Nada disso impede o uso local: o Room continua sendo a autoridade do aparelho.
        assertEquals("Offline 1", a.templateName(t1))

        a.api.offline = false
        a.sync()
        b.sync()

        assertEquals("Offline 1", b.templateName(t1))
        assertEquals("Offline 2", b.templateName(t2))
        assertEquals(77.5f, b.measurementWeight(medida))
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun historicoConcluidoDivergenteEntreAparelhosViraConflitoDeIntegridade() = runTest {
        val exercicioA = a.installCanonicalExercise("canonical.supino", "Supino")
        b.installCanonicalExercise("canonical.supino", "Supino")

        val sessionSyncId = a.completeSession(exercicioA, weight = 100f)
        a.sync()
        b.sync()

        // B tenta enviar a **mesma** sessão com conteúdo diferente — o que só acontece com dado
        // corrompido, e que o protocolo precisa recusar em vez de reescrever o passado.
        b.mutations.mutate {
            val session = b.database.workoutDao().getSessionWithDetailsBySyncId(sessionSyncId)!!
            val set = session.sortedExercises.first().sets.first()
            b.database.workoutDao().insertSetLogs(listOf(set.copy(id = 0, setNumber = 2, weight = 999f)))
            upsert(SyncEntityType.WORKOUT_SESSION, sessionSyncId)
        }
        val outcome = b.sync() as SyncOutcome.Success

        assertEquals(1, outcome.conflicts)
        assertEquals(
            SyncConflictKind.IMMUTABLE_HISTORY.name,
            b.conflicts().single().kind
        )
        // O servidor continua com a sessão original, em revision 1.
        assertEquals(1L, server.revisionOf(ownerUid, SyncEntityType.WORKOUT_SESSION, sessionSyncId))
        // E A, que não mexeu em nada, continua com o histórico dele intacto.
        a.sync()
        assertEquals(1, a.rowCount("set_logs"))
        assertEquals(0, a.rowCount("xp_transactions"))
    }

    @Test
    fun aparelhoNovoRecebeODatasetInteiroDoZero() = runTest {
        a.installCanonicalExercise("canonical.supino", "Supino")
        val t1 = a.newTemplate("Treino A")
        val t2 = a.newTemplate("Treino B")
        val medida = a.newMeasurement(weightKg = 80f)
        val custom = a.newCustomExercise("Rosca do vizinho")
        a.sync()

        val novo = SyncDevice(server, ownerUid, "device-novo")
        try {
            novo.bind()
            novo.installCanonicalExercise("canonical.supino", "Supino")
            val outcome = novo.sync() as SyncOutcome.Success

            assertEquals(4, outcome.applied)
            assertEquals("Treino A", novo.templateName(t1))
            assertEquals("Treino B", novo.templateName(t2))
            assertEquals(80f, novo.measurementWeight(medida))
            assertNotEquals(null, novo.database.workoutDao().getExerciseBySyncId(custom))
            // Um aparelho que acabou de receber tudo não tem nada a devolver.
            assertEquals(0, novo.pendingCount())
        } finally {
            novo.close()
        }
    }
}
