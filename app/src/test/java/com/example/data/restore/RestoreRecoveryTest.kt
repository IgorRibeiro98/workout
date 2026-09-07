package com.example.data.restore

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O que acontece quando o processo morre no meio de um restore (T16.5).
 *
 * ```text
 * DOWNLOADING / VALIDATED / SAFETY_SNAPSHOT_CREATED  → nada foi aplicado  → encerrar
 * ROOM_APPLIED                                        → dado já substituído → retomar
 * ROOM_APPLIED sem o snapshot baixado                 → não dá para retomar → desfazer
 * PREFERENCES_APPLIED                                 → só faltou concluir  → concluir
 * ```
 *
 * A fase persistida é a autoridade — e é ela que impede a pergunta impossível "o restore terminou?"
 * depois de um `onDestroy` no meio do caminho. Enquanto a resposta não existir, **nenhuma tela diz
 * que terminou**, e o app resolve a pendência antes de qualquer outra escrita.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RestoreRecoveryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var harness: RestoreHarness
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val uid = "uid-da-conta-a"

    @Before
    fun setUp() = runTest {
        database = RestoreHarness.database(context)
        harness = RestoreHarness(
            database = database,
            context = context,
            filesRoot = File(folder.newFolder("aparelho"), "restore")
        )
        RestoreDatasetFixture.seedCatalog(database)
        RestoreDatasetFixture.seedPersonalData(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // --------------------------------------------------- interrupções antes da mutação

    @Test
    fun `morte do processo durante o download nao deixa nada aplicado`() = runTest {
        val before = harness.semanticFingerprint()
        val attempt = crashedAttempt(RestorePhase.DOWNLOADING)

        val result = harness.repository.recover()

        assertEquals(RestoreRecoveryResult.Discarded, result)
        assertEquals("zero alteração local", before, harness.semanticFingerprint())
        assertFinished(attempt)
    }

    @Test
    fun `morte do processo depois da validacao nao deixa nada aplicado`() = runTest {
        val before = harness.semanticFingerprint()
        val attempt = crashedAttempt(RestorePhase.VALIDATED)

        val result = harness.repository.recover()

        assertEquals(RestoreRecoveryResult.Discarded, result)
        assertEquals(before, harness.semanticFingerprint())
        assertFinished(attempt)
    }

    @Test
    fun `morte do processo depois do snapshot de seguranca nao deixa nada aplicado`() = runTest {
        val before = harness.semanticFingerprint()
        val attempt = crashedAttempt(RestorePhase.SAFETY_SNAPSHOT_CREATED)

        val result = harness.repository.recover()

        assertEquals(RestoreRecoveryResult.Discarded, result)
        assertEquals(
            "o snapshot de segurança existe justamente porque a mutação ainda não aconteceu",
            before,
            harness.semanticFingerprint()
        )
        assertFinished(attempt)
    }

    @Test
    fun `enquanto ha tentativa interrompida, um novo restore e recusado`() = runTest {
        crashedAttempt(RestorePhase.ROOM_APPLIED)
        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())

        val preparation = harness.repository.prepare(backup, uid)

        assertEquals(
            RestoreError.RESTORE_RECOVERY_REQUIRED,
            (preparation as RestorePreparation.Failed).error
        )
        assertEquals("nada é baixado antes de resolver a pendência", 0, harness.api.downloadCount)
    }

    // ------------------------------------------------------- interrupção depois do Room

    @Test
    fun `morte do processo entre o Room e as preferencias retoma na proxima abertura`() = runTest {
        harness.settingsManager.setWeeklyGoal(6)
        val snapshotBody = harness.canonicalSnapshotOfCurrentState()
        val backup = harness.api.publish(snapshotBody)

        // O aparelho aplicou o Room e morreu antes de gravar as preferências.
        val attempt = applyRoomAndCrash(backup)
        harness.settingsManager.setWeeklyGoal(1)

        val result = harness.repository.recover()

        assertEquals(RestoreRecoveryResult.Resumed, result)
        assertEquals(
            "a fase pendente é concluída, e só então o restore termina",
            6,
            harness.settingsManager.weeklyGoalFlow.first()
        )
        val finished = database.restoreAttemptDao().byId(attempt.id)!!
        assertEquals(RestorePhase.COMPLETED.name, finished.status)
        assertNull(database.restoreAttemptDao().oldestUnfinished())
        assertNoLeftoverFiles()
    }

    @Test
    fun `sem o snapshot baixado, a recuperacao desfaz usando o snapshot de seguranca`() = runTest {
        val before = harness.semanticFingerprint()
        val emptyBody = emptySnapshotBody()
        val backup = harness.api.publish(emptyBody)

        val attempt = applyRoomAndCrash(backup)
        // O dataset já é o do backup — vazio.
        assertEquals(0, database.workoutDao().getCompletedSessionSyncIds().size)
        // E o arquivo baixado sumiu (limpeza do sistema, espaço, o que for): retomar é impossível.
        File(attempt.downloadPath!!).delete()

        val result = harness.repository.recover()

        assertEquals(RestoreRecoveryResult.RolledBack, result)
        assertEquals(
            "o estado anterior é reconstruído a partir do snapshot de segurança",
            before,
            harness.semanticFingerprint()
        )
        assertEquals(3, database.workoutDao().getCompletedSessionSyncIds().size)
        assertNull(database.restoreAttemptDao().oldestUnfinished())
    }

    @Test
    fun `o rollback devolve o dataset a condicao de sem dono`() = runTest {
        val backup = harness.api.publish(emptySnapshotBody())
        val attempt = applyRoomAndCrash(backup)
        assertNotNull(
            "durante a tentativa o vínculo existe",
            database.cloudDataBindingDao().get()
        )
        File(attempt.downloadPath!!).delete()

        harness.repository.recover()

        assertNull(
            "desfazer e deixar o vínculo para trás faria a conta apontar para dados que não são " +
                "do backup dela",
            database.cloudDataBindingDao().get()
        )
    }

    @Test
    fun `interrupcao depois das preferencias apenas conclui`() = runTest {
        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())
        val attempt = applyRoomAndCrash(backup)
        database.restoreAttemptDao().updateStatus(
            attempt.id,
            RestorePhase.PREFERENCES_APPLIED.name,
            1_800_000_000_000L
        )

        val result = harness.repository.recover()

        assertEquals(RestoreRecoveryResult.Resumed, result)
        assertEquals(
            RestorePhase.COMPLETED.name,
            database.restoreAttemptDao().byId(attempt.id)!!.status
        )
        assertNoLeftoverFiles()
    }

    @Test
    fun `sem tentativa interrompida a recuperacao nao faz nada`() = runTest {
        val before = harness.semanticFingerprint()

        val result = harness.repository.recover()

        assertEquals(RestoreRecoveryResult.NothingToRecover, result)
        assertEquals(before, harness.semanticFingerprint())
    }

    @Test
    fun `arquivos de tentativas encerradas nao se acumulam`() = runTest {
        val orfao = File(harness.filesRoot, "tentativa-antiga.json")
        orfao.parentFile?.mkdirs()
        orfao.writeText("{}")

        harness.repository.recover()

        assertFalse(
            "um snapshot que não pertence a tentativa viva nenhuma não pode ficar no aparelho",
            orfao.exists()
        )
    }

    // ------------------------------------------------------------------------- helpers

    /** Uma tentativa parada em [phase], como um process death a deixaria. */
    private suspend fun crashedAttempt(phase: RestorePhase): RestoreAttemptEntity {
        val attemptId = "tentativa-interrompida"
        val file = harness.files.downloadFile(attemptId)
        file.parentFile?.mkdirs()
        file.writeText(harness.canonicalSnapshotOfCurrentState(), Charsets.UTF_8)

        val id = database.restoreAttemptDao().insert(
            RestoreAttemptEntity(
                restoreAttemptId = attemptId,
                backupId = "backup-escolhido",
                ownerUid = uid,
                payloadHash = "hash",
                backupSchemaVersion = 1,
                backupCreatedAt = 1_700_000_000_000L,
                downloadPath = file.absolutePath,
                safetySnapshotPath = null,
                datasetWasUnbound = true,
                status = phase.name,
                createdAt = 1_800_000_000_000L,
                updatedAt = 1_800_000_000_000L
            )
        )
        return database.restoreAttemptDao().byId(id)!!
    }

    /**
     * Executa um restore de verdade até o commit do Room e para ali — como um `onDestroy` entre a
     * transação e a fase das preferências.
     *
     * Usa os mesmos componentes do repositório (snapshot de segurança + transação), e não uma
     * simulação: o que precisa ser recuperado é o estado que o caminho real produz.
     */
    private suspend fun applyRoomAndCrash(
        backup: com.example.data.backup.BackupMetadataDto
    ): RestoreAttemptEntity {
        val preparation = harness.repository.prepare(backup, uid)
        assertTrue("$preparation", preparation is RestorePreparation.Ready)
        val ready = preparation as RestorePreparation.Ready

        val attempt = database.restoreAttemptDao().byRestoreAttemptId(ready.restoreAttemptId)!!
        val safety = harness.safetySnapshots.create(attempt.restoreAttemptId)
        database.restoreAttemptDao()
            .updateSafetySnapshotPath(attempt.id, safety.absolutePath, 1_800_000_000_000L)
        database.restoreAttemptDao()
            .updateStatus(attempt.id, RestorePhase.SAFETY_SNAPSHOT_CREATED.name, 1_800_000_000_000L)

        harness.transaction.apply(
            plan = ready.plan,
            binding = RestoreBindingOutcome.Bind(
                ownerUid = uid,
                deviceId = "device-de-teste",
                backupId = attempt.backupId,
                backupCreatedAt = attempt.backupCreatedAt
            )
        )
        database.restoreAttemptDao()
            .updateStatus(attempt.id, RestorePhase.ROOM_APPLIED.name, 1_800_000_000_000L)

        return database.restoreAttemptDao().byId(attempt.id)!!
    }

    private suspend fun emptySnapshotBody(): String {
        val empty = RestoreHarness.database(context)
        return try {
            RestoreHarness(
                database = empty,
                context = context,
                filesRoot = File(folder.newFolder("vazio"), "restore")
            ).canonicalSnapshotOfCurrentState()
        } finally {
            empty.close()
        }
    }

    private suspend fun assertFinished(attempt: RestoreAttemptEntity) {
        assertEquals(
            RestorePhase.ABANDONED.name,
            database.restoreAttemptDao().byId(attempt.id)!!.status
        )
        assertNull(database.restoreAttemptDao().oldestUnfinished())
        assertNoLeftoverFiles()
    }

    private fun assertNoLeftoverFiles() {
        val leftovers = harness.filesRoot.listFiles().orEmpty()
        assertTrue(
            "tentativa encerrada não deixa snapshot no aparelho: ${leftovers.map { it.name }}",
            leftovers.isEmpty()
        )
    }
}
