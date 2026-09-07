package com.example.data.restore

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.backup.BackupMetadataDto
import com.example.data.backup.CloudDataBindingEntity
import com.example.data.local.AppDatabase
import com.example.data.sync.CloudSyncState
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * De quem são os dados depois de um restore (T16.5).
 *
 * ```text
 * dataset sem dono  + conta A  →  restore de A permitido  →  dataset passa a ser de A
 * dataset de A      + conta A  →  restore de A permitido  →  dataset continua de A
 * dataset de A      + conta B  →  BLOQUEADO               →  nada muda, nem o vínculo
 * ```
 *
 * A regra que este arquivo protege é a mais fácil de quebrar com boa intenção: "já que B está
 * logado, passe os dados para B". Trocar o dono de um conjunto de dados é uma política que o Spark
 * ainda não tem — e resolver isso por efeito colateral de um restore seria decidir pelo usuário no
 * caminho menos visível do app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RestoreAccountRulesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var harness: RestoreHarness
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val uidA = "uid-da-conta-A"
    private val uidB = "uid-da-conta-B"

    @Before
    fun setUp() = runTest {
        database = RestoreHarness.database(context)
        harness = RestoreHarness(
            database = database,
            context = context,
            filesRoot = File(folder.newFolder("aparelho"), "restore")
        )
        RestoreDatasetFixture.seedCatalog(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------- dataset sem dono

    @Test
    fun `dataset sem dono restaura backup da conta atual e passa a pertencer a ela`() = runTest {
        val backup = publishBackupOf(uidA)
        assertNull("o dataset começa sem dono", database.cloudDataBindingDao().get())

        restore(backup, uidA)

        val binding = database.cloudDataBindingDao().get()
        assertNotNull("o vínculo nasce com o restore confirmado", binding)
        assertEquals(uidA, binding!!.ownerUid)
        assertEquals(CloudSyncState.ENABLED.name, binding.state)
        assertEquals(
            "o backup restaurado é a última cópia conhecida — e não um backup novo",
            backup.backupId,
            binding.lastSuccessfulBackupId
        )
        assertEquals(backup.createdAt, binding.lastSuccessfulBackupAt)
    }

    @Test
    fun `o vinculo so nasce no commit, nunca na preparacao`() = runTest {
        val backup = publishBackupOf(uidA)

        val preparation = harness.repository.prepare(backup, uidA)

        assertTrue("$preparation", preparation is RestorePreparation.Ready)
        assertNull(
            "baixar e validar não dá dono a dado nenhum",
            database.cloudDataBindingDao().get()
        )
    }

    @Test
    fun `restore que falha nao deixa vinculo para tras`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
        val backup = harness.api.publish(body, corruptBody = "$body ")

        harness.repository.prepare(backup, uidA)

        assertNull(
            "um dataset que era sem dono continua sem dono depois de uma falha",
            database.cloudDataBindingDao().get()
        )
    }

    // ------------------------------------------------------------------- dataset com dono

    @Test
    fun `dataset da conta A aceita restore da propria conta A`() = runTest {
        bindTo(uidA)
        val backup = publishBackupOf(uidA)

        restore(backup, uidA)

        val binding = database.cloudDataBindingDao().get()!!
        assertEquals("o dono continua o mesmo", uidA, binding.ownerUid)
        assertEquals(backup.backupId, binding.lastSuccessfulBackupId)
    }

    @Test
    fun `dataset da conta A bloqueia restore com a conta B conectada`() = runTest {
        bindTo(uidA)
        val backup = publishBackupOf(uidB)

        val preparation = harness.repository.prepare(backup, uidB)

        assertTrue("$preparation", preparation is RestorePreparation.Failed)
        assertEquals(
            RestoreError.ACCOUNT_MISMATCH,
            (preparation as RestorePreparation.Failed).error
        )
        assertEquals("o dono não é reatribuído", uidA, database.cloudDataBindingDao().get()!!.ownerUid)
        assertEquals("e nada foi baixado", 0, harness.api.downloadCount)
    }

    @Test
    fun `a conta B nem chega a listar os backups de um dataset da conta A`() = runTest {
        bindTo(uidA)

        val listing = harness.repository.availableBackups(uidB)

        assertTrue("$listing", listing is RestoreListing.Failed)
        assertEquals(RestoreError.ACCOUNT_MISMATCH, (listing as RestoreListing.Failed).error)
        assertEquals("nenhuma requisição sai", 0, harness.api.listCount)
    }

    @Test
    fun `sem conta conectada nao ha o que listar nem restaurar`() = runTest {
        val backup = publishBackupOf(uidA)

        val listing = harness.repository.availableBackups(null)
        val preparation = harness.repository.prepare(backup, null)

        assertEquals(
            RestoreError.AUTH_REQUIRED,
            (listing as RestoreListing.Failed).error
        )
        assertEquals(
            RestoreError.AUTH_REQUIRED,
            (preparation as RestorePreparation.Failed).error
        )
        assertEquals(0, harness.api.listCount)
        assertEquals(0, harness.api.downloadCount)
    }

    // ------------------------------------------------------- conta muda no meio do caminho

    @Test
    fun `trocar de conta entre o preview e a confirmacao cancela o restore`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val fingerprint = harness.semanticFingerprint()
        val backup = publishBackupOf(uidA)
        val ready = prepare(backup, uidA)

        // O usuário saiu da conta A e entrou na B enquanto o preview estava aberto.
        val outcome = harness.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = uidB,
            confirmed = true
        )

        assertTrue("$outcome", outcome is RestoreOutcome.Failed)
        assertEquals(RestoreError.ACCOUNT_CHANGED, (outcome as RestoreOutcome.Failed).error)
        assertEquals("zero alteração local", fingerprint, harness.semanticFingerprint())
        assertNull("e nenhum vínculo nasce", database.cloudDataBindingDao().get())
    }

    @Test
    fun `sair da conta entre o preview e a confirmacao cancela o restore`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val fingerprint = harness.semanticFingerprint()
        val backup = publishBackupOf(uidA)
        val ready = prepare(backup, uidA)

        val outcome = harness.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = null,
            confirmed = true
        )

        assertEquals(RestoreError.AUTH_REQUIRED, (outcome as RestoreOutcome.Failed).error)
        assertEquals("zero alteração local", fingerprint, harness.semanticFingerprint())
    }

    // ------------------------------------------------------------------- confirmação

    @Test
    fun `sem confirmacao explicita nada e substituido`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val fingerprint = harness.semanticFingerprint()
        val backup = publishBackupOf(uidA)
        val ready = prepare(backup, uidA)

        val outcome = harness.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = uidA,
            confirmed = false
        )

        assertEquals(
            RestoreError.RESTORE_CONFIRMATION_REQUIRED,
            (outcome as RestoreOutcome.Failed).error
        )
        assertEquals(fingerprint, harness.semanticFingerprint())
    }

    // ------------------------------------------------------------------- efeitos colaterais

    @Test
    fun `restore nao cria backup remoto nem tentativa de backup`() = runTest {
        val backup = publishBackupOf(uidA)

        restore(backup, uidA)

        assertEquals(
            "restaurar não é fazer backup: nenhuma tentativa de upload nasce",
            0,
            database.backupAttemptDao().count()
        )
    }

    @Test
    fun `o snapshot remoto continua disponivel depois de restaurado`() = runTest {
        val backup = publishBackupOf(uidA)

        restore(backup, uidA)

        val listing = harness.repository.availableBackups(uidA)
        assertTrue("$listing", listing is RestoreListing.Available)
        assertEquals(
            "restaurar não consome o backup",
            listOf(backup.backupId),
            (listing as RestoreListing.Available).backups.map { it.backupId }
        )
    }

    // ------------------------------------------------------------------- helpers

    /** Um backup "da conta X": o servidor de teste já só devolve o que é daquela conta. */
    private suspend fun publishBackupOf(ownerUid: String): BackupMetadataDto =
        harness.api.publish(
            harness.canonicalSnapshotOfCurrentState(clientBackupId = "backup-de-$ownerUid")
        )

    private suspend fun bindTo(ownerUid: String) {
        database.cloudDataBindingDao().insertIfAbsent(
            CloudDataBindingEntity(
                ownerUid = ownerUid,
                state = CloudSyncState.ENABLED.name,
                boundAt = 1_700_000_000_000L,
                deviceId = "device-de-teste"
            )
        )
    }

    private suspend fun prepare(
        backup: BackupMetadataDto,
        uid: String
    ): RestorePreparation.Ready {
        val preparation = harness.repository.prepare(backup, uid)
        assertTrue("$preparation", preparation is RestorePreparation.Ready)
        return preparation as RestorePreparation.Ready
    }

    private suspend fun restore(backup: BackupMetadataDto, uid: String) {
        val ready = prepare(backup, uid)
        val outcome = harness.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = uid,
            confirmed = true
        )
        assertTrue("$outcome", outcome is RestoreOutcome.Success)
    }
}
