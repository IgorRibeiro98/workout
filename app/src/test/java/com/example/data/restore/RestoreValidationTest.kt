package com.example.data.restore

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.backup.BackupMetadataDto
import com.example.data.local.AppDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O que impede um backup ruim de virar dado ruim (T16.5).
 *
 * ```text
 * download → hash → versão → schema → semântica → plano
 *                     ↑
 *          qualquer recusa aqui  ⇒  ZERO alteração local
 * ```
 *
 * Cada teste desta classe verifica **duas** coisas: que a recusa aconteceu com o erro certo, e que
 * o aparelho continua exatamente como estava. A segunda é a que importa — um restore que recusa e
 * mesmo assim mexeu no banco seria pior do que um que aceita tudo, porque falharia em silêncio.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RestoreValidationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var harness: RestoreHarness
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val uid = "uid-da-conta-a"

    private lateinit var fingerprintBefore: String

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
        fingerprintBefore = harness.semanticFingerprint()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------------- integridade

    @Test
    fun `um byte trocado no conteudo bloqueia o restore`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
        // A metadata continua descrevendo o snapshot íntegro; o corpo servido, não. É exatamente o
        // que aconteceria com corrupção no caminho, no disco ou num proxy que reempacota resposta.
        val backup = harness.api.publish(body, corruptBody = body.replaceFirst("Treino A", "Treino X"))

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.BACKUP_INTEGRITY_ERROR)
        assertNothingChanged()
    }

    @Test
    fun `hash conferido e o do conteudo recebido, nao o declarado no corpo`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
        val backup = harness.api.publish(body).copy(payloadHash = "0".repeat(64))

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.BACKUP_INTEGRITY_ERROR)
        assertNothingChanged()
    }

    @Test
    fun `o arquivo de um download recusado nao fica para tras`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
        val backup = harness.api.publish(body, corruptBody = "$body ")

        harness.repository.prepare(backup, uid)

        val leftovers = File(folder.root, "aparelho/restore").listFiles().orEmpty()
        assertTrue("nenhum snapshot inválido pode ficar guardado", leftovers.isEmpty())
    }

    // ------------------------------------------------------------------------- versões

    @Test
    fun `versao de formato mais nova e recusada sem sequer baixar`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
        val backup = harness.api.publish(body).copy(
            backupSchemaVersion = com.example.data.backup.BackupContract.SCHEMA_VERSION + 1
        )

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.UNSUPPORTED_BACKUP_VERSION)
        assertEquals("não faz sentido baixar o que não se sabe ler", 0, harness.api.downloadCount)
        assertNothingChanged()
    }

    @Test
    fun `versao de formato mais nova dentro do corpo tambem e recusada`() = runTest {
        // Metadata diz v1, conteúdo diz v2: o leitor confere o documento, não só o cabeçalho.
        val body = harness.canonicalSnapshotOfCurrentState()
            .replaceFirst("\"backupSchemaVersion\":1", "\"backupSchemaVersion\":2")
        val backup = harness.api.publish(body)

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.UNSUPPORTED_BACKUP_VERSION)
        assertNothingChanged()
    }

    @Test
    fun `as versoes suportadas sao exatamente a v1`() {
        assertEquals(setOf(1), RestoreContract.SUPPORTED_BACKUP_SCHEMA_VERSIONS)
        assertTrue(RestoreContract.supports(1))
        assertFalse(RestoreContract.supports(2))
        assertFalse(RestoreContract.supports(0))
    }

    @Test
    fun `entitySchemaVersion desconhecida e recusada, sem ignorar o item`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
            .replaceFirst("\"entitySchemaVersion\":1", "\"entitySchemaVersion\":9")
        val backup = harness.api.publish(body)

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.UNSUPPORTED_ENTITY_VERSION)
        assertNothingChanged()
    }

    // ------------------------------------------------------------------------- estrutura

    @Test
    fun `entityType desconhecido nao e ignorado`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
            .replaceFirst("\"entityType\":\"WEEKLY_GOAL\"", "\"entityType\":\"TIPO_DO_FUTURO\"")
        val backup = harness.api.publish(body)

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.INVALID_BACKUP)
        assertNothingChanged()
    }

    @Test
    fun `campo desconhecido no payload e recusado, nao guardado em silencio`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
            .replaceFirst("\"goal\":4", "\"campoDoFuturo\":true,\"goal\":4")
        val backup = harness.api.publish(body)

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.INVALID_BACKUP)
        assertNothingChanged()
    }

    @Test
    fun `json valido com semantica invalida e recusado`() = runTest {
        // Hash íntegro, JSON bem formado, contrato violado: a identidade do item não corresponde ao
        // conteúdo dele. Forma válida não é o mesmo que significado válido.
        val body = harness.canonicalSnapshotOfCurrentState()
            .replaceFirst(
                "\"syncId\":\"${RestoreDatasetFixture.MEASUREMENT_SYNC_ID}\"}",
                "\"syncId\":\"00000000-0000-4000-8000-000000000000\"}"
            )
        val backup = harness.api.publish(body)

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.INVALID_BACKUP)
        assertNothingChanged()
    }

    @Test
    fun `um agregado invalido entre muitos validos recusa o restore inteiro`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
            .replaceFirst("\"status\":\"COMPLETED\"", "\"status\":\"IN_PROGRESS\"")
        val backup = harness.api.publish(body)

        val preparation = harness.repository.prepare(backup, uid)

        // Não existe "restaura 11 e ignora 1": um dataset parcial parece íntegro e não é.
        assertFailed(preparation, RestoreError.INVALID_BACKUP)
        assertNothingChanged()
    }

    @Test
    fun `contagem divergente entre metadata e conteudo bloqueia o restore`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
        val backup = harness.api.publish(body).copy(itemCount = 99)

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.INVALID_BACKUP)
        assertNothingChanged()
    }

    // ------------------------------------------------------------------------- referências

    @Test
    fun `exercicio de catalogo ausente no aparelho bloqueia antes de qualquer escrita`() = runTest {
        val body = harness.canonicalSnapshotOfCurrentState()
            .replace(RestoreDatasetFixture.CANONICAL_SUPINO, "exercicio-que-nao-existe")
        val backup = harness.api.publish(body)

        val preparation = harness.repository.prepare(backup, uid)

        // Sem *fuzzy matching*: o Spark não escolhe "o exercício mais parecido".
        assertFailed(preparation, RestoreError.MISSING_CATALOG_EXERCISE)
        assertNothingChanged()
    }

    @Test
    fun `referencia interna quebrada e recusada`() = runTest {
        val backup = publishFixture("backup-v1-invalid-reference")

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.INVALID_BACKUP)
        assertNothingChanged()
    }

    // ------------------------------------------------------------- fixtures do contrato

    @Test
    fun `as fixtures invalidas do contrato sao recusadas tambem no restore`() = runTest {
        val invalid = listOf(
            "backup-v1-invalid-id",
            "backup-v1-duplicate-item",
            "backup-v1-invalid-reference",
            "backup-v1-unsupported-version"
        )

        invalid.forEach { name ->
            val backup = publishFixture(name)
            val preparation = harness.repository.prepare(backup, uid)
            assertTrue(
                "a fixture $name precisa ser recusada",
                preparation is RestorePreparation.Failed
            )
            assertNothingChanged()
        }
    }

    @Test
    fun `a fixture minima do contrato e valida e descreve um dataset vazio`() = runTest {
        val backup = publishFixture("backup-v1-minimal")

        val preparation = harness.repository.prepare(backup, uid)

        assertTrue("$preparation", preparation is RestorePreparation.Ready)
        assertEquals(0, (preparation as RestorePreparation.Ready).plan.counts.total)
        // Preparar não altera nada: a substituição só acontece na confirmação.
        assertDataUnchanged()
    }

    @Test
    fun `a fixture completa do contrato e restauravel quando o catalogo existe`() = runTest {
        val backup = publishFixture("backup-v1-complete")

        val preparation = harness.repository.prepare(backup, uid)

        assertTrue("$preparation", preparation is RestorePreparation.Ready)
        val plan = (preparation as RestorePreparation.Ready).plan
        assertEquals("o contrato tem nove agregados", 9, plan.snapshot.itemCount)
        assertDataUnchanged()
    }

    // ------------------------------------------------------------------------- transporte

    @Test
    fun `backend fora do ar nao altera nada e permite tentar de novo`() = runTest {
        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())
        harness.api.nextDownloadResult = RestoreDownloadResult.Unavailable

        val first = harness.repository.prepare(backup, uid)
        assertFailed(first, RestoreError.UNAVAILABLE)
        assertNothingChanged()

        // Repetir é seguro: o download é read-only no servidor, e a tentativa anterior foi
        // encerrada em vez de ficar bloqueando o aparelho.
        harness.api.nextDownloadResult = null
        val second = harness.repository.prepare(backup, uid)
        assertTrue("$second", second is RestorePreparation.Ready)
    }

    @Test
    fun `backup criado por servidor antigo diz que nao pode ser restaurado`() = runTest {
        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())
        harness.api.nextDownloadResult = RestoreDownloadResult.ContentUnavailable

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.BACKUP_CONTENT_UNAVAILABLE)
        assertNothingChanged()
    }

    @Test
    fun `payload acima do teto e recusado`() = runTest {
        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())
        harness.api.nextDownloadResult = RestoreDownloadResult.TooLarge

        val preparation = harness.repository.prepare(backup, uid)

        assertFailed(preparation, RestoreError.BACKUP_TOO_LARGE)
        assertNothingChanged()
    }

    // ------------------------------------------------------------------------- helpers

    private fun publishFixture(name: String): BackupMetadataDto =
        harness.api.publish(RestoreContractFixtures.text(name), backupId = name)

    private fun assertFailed(preparation: RestorePreparation, expected: RestoreError) {
        assertTrue("esperava falha, veio $preparation", preparation is RestorePreparation.Failed)
        assertEquals(expected, (preparation as RestorePreparation.Failed).error)
    }

    /** O invariante que vale para toda recusa: o aparelho continua exatamente como estava. */
    private suspend fun assertNothingChanged() {
        assertDataUnchanged()
        assertNull(
            "e nenhuma recusa pode deixar uma tentativa em aberto travando o aparelho",
            database.restoreAttemptDao().oldestUnfinished()
        )
    }

    /**
     * O dataset não mudou.
     *
     * Separado da checagem de tentativa porque uma preparação **bem-sucedida** deixa a tentativa em
     * `VALIDATED` de propósito: ela está esperando a confirmação do usuário, e nada foi alterado.
     */
    private suspend fun assertDataUnchanged() {
        assertEquals(
            "nada pode alterar dado local antes da confirmação",
            fingerprintBefore,
            harness.semanticFingerprint()
        )
    }
}
