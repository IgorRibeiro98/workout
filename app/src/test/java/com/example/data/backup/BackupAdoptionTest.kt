package com.example.data.backup

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.WorkoutProgramEntity
import com.example.data.repository.WorkoutRepository
import com.example.data.sync.CloudSyncScope
import com.example.data.sync.CloudSyncState
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.IdGenerator
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncAggregateSnapshotBuilder
import com.example.data.sync.SyncMutationCoordinator
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
 * A política de adoção da T16.4: **login não associa dados a uma conta**.
 *
 * ```text
 * Google login  →  todos os dados locais passam a ser da conta      ← PROIBIDO
 *
 * Google login  →  dados continuam locais e sem dono
 *      ↓ "Ativar backup" + confirmação explícita
 * dataset passa a pertencer àquela Conta Spark                      ← o que este teste prova
 * ```
 *
 * Room de verdade, repositórios de verdade, servidor dublê. Nada aqui abre socket.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BackupAdoptionTest {

    private lateinit var database: AppDatabase
    private lateinit var api: FakeBackupApi
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val uidA = "uid-da-conta-A"
    private val uidB = "uid-da-conta-B"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = FakeBackupApi()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------------------- login

    @Test
    fun `sem conta nao existe backup e nada e enviado`() = runTest {
        val repository = repository()

        val result = repository.backupNow(currentUid = null, confirmedAdoption = false)

        assertEquals(BackupOperation.AuthRequired, result)
        assertEquals("nenhuma requisição pode sair sem conta", 0, api.callCount)
        assertNull("nada pode ser vinculado", database.cloudDataBindingDao().get())
    }

    @Test
    fun `entrar na conta nao ativa backup nem vincula dado`() = runTest {
        val repository = repository()

        // "Entrar" aqui é ter um uid disponível. Nenhum backup é chamado por isso: o teste
        // simplesmente não chama, e o que ele verifica é que o estado do banco não mudou.
        assertNull(database.cloudDataBindingDao().get())
        assertEquals(CloudSyncScope.Disabled, scopeProvider().current())
        assertEquals(0, api.callCount)

        // E, mesmo pedindo backup, sem confirmação nada é adotado.
        val result = repository.backupNow(currentUid = uidA, confirmedAdoption = false)

        assertEquals(BackupOperation.AdoptionRequired, result)
        assertNull(database.cloudDataBindingDao().get())
        assertEquals(0, api.callCount)
        assertEquals(0, database.backupAttemptDao().count())
    }

    // -------------------------------------------------------------------------- adoção

    @Test
    fun `a confirmacao explicita vincula o dataset a conta atual`() = runTest {
        val repository = repository()

        val result = repository.backupNow(currentUid = uidA, confirmedAdoption = true)

        assertTrue(result is BackupOperation.Success)
        val binding = database.cloudDataBindingDao().get()
        assertNotNull(binding)
        assertEquals(uidA, binding!!.ownerUid)
        // Só depois da confirmação do servidor o vínculo passa a `ENABLED`.
        assertEquals(CloudSyncState.ENABLED.name, binding.state)
        assertEquals(1, api.callCount)
    }

    @Test
    fun `cancelar a confirmacao nao vincula nada e nao envia nada`() = runTest {
        val repository = repository()

        // Cancelar é, do ponto de vista do domínio, simplesmente não confirmar.
        repository.backupNow(currentUid = uidA, confirmedAdoption = false)

        assertNull(database.cloudDataBindingDao().get())
        assertEquals(0, database.backupAttemptDao().count())
        assertEquals(0, api.callCount)
        assertEquals(CloudSyncScope.Disabled, scopeProvider().current())
    }

    @Test
    fun `a adocao liga a Outbox no escopo do dono, e nao antes`() = runTest {
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))

        // Antes da adoção: alteração local acontece, e nenhuma intenção remota nasce.
        workoutRepository().addTemplate(programId, "Treino A", "A", 0)
        assertEquals("nuvem desligada não produz fila", 0, database.syncOutboxDao().count())

        repository().backupNow(currentUid = uidA, confirmedAdoption = true)

        // Depois da adoção: a mesma operação passa a registrar, no escopo daquela conta.
        workoutRepository().addTemplate(programId, "Treino B", "B", 1)
        val entries = database.syncOutboxDao().pendingFor(uidA)
        assertEquals(1, entries.size)
        assertEquals(uidA, entries.single().ownerUid)
    }

    // ------------------------------------------------------------------ troca de conta

    @Test
    fun `sair da conta nao remove o vinculo`() = runTest {
        repository().backupNow(currentUid = uidA, confirmedAdoption = true)

        // `currentUid = null` é a sessão ausente: o vínculo continua exatamente como estava.
        val result = repository().backupNow(currentUid = null, confirmedAdoption = false)

        assertEquals(BackupOperation.AuthRequired, result)
        assertEquals(uidA, database.cloudDataBindingDao().get()?.ownerUid)
        assertEquals(CloudSyncScope.Enabled(uidA), scopeProvider().current())
    }

    @Test
    fun `alteracao offline depois da adocao continua no escopo do dono`() = runTest {
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        repository().backupNow(currentUid = uidA, confirmedAdoption = true)

        // Sem sessão Firebase ativa, o núcleo continua funcionando e a intenção é registrada para
        // a conta dona — não para "ninguém" e não para quem entrar depois.
        workoutRepository().addTemplate(programId, "Treino offline", "O", 0)

        val entries = database.syncOutboxDao().pendingFor(uidA)
        assertEquals(1, entries.size)
        assertEquals(uidA, entries.single().ownerUid)
        assertEquals("nenhuma rede acontece sem conta", 1, api.callCount)
    }

    @Test
    fun `entrar com outra conta nao transfere o dataset`() = runTest {
        repository().backupNow(currentUid = uidA, confirmedAdoption = true)
        val callsAfterAdoption = api.callCount

        // A conta B tenta, inclusive confirmando: o vínculo é de A e continua sendo.
        val result = repository().backupNow(currentUid = uidB, confirmedAdoption = true)

        assertEquals(BackupOperation.AccountMismatch(uidA), result)
        assertEquals(uidA, database.cloudDataBindingDao().get()?.ownerUid)
        assertEquals("nada pode subir no descompasso", callsAfterAdoption, api.callCount)
        assertEquals(CloudSyncScope.Enabled(uidA), scopeProvider().current())
    }

    @Test
    fun `no descompasso de conta o nucleo local continua funcionando`() = runTest {
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        repository().backupNow(currentUid = uidA, confirmedAdoption = true)
        repository().backupNow(currentUid = uidB, confirmedAdoption = true)

        // Criar treino, executar e concluir não passam por nuvem nenhuma.
        val templateId = workoutRepository().addTemplate(programId, "Treino local", "L", 0)

        assertNotNull(database.workoutDao().getTemplateById(templateId))
        // A mutação continua sendo registrada no escopo do **dono**, não do usuário conectado.
        assertTrue(database.syncOutboxDao().pendingFor(uidA).isNotEmpty())
        assertTrue(database.syncOutboxDao().pendingFor(uidB).isEmpty())
    }

    @Test
    fun `o backend fora do ar nao quebra o nucleo nem apaga o vinculo`() = runTest {
        repository().backupNow(currentUid = uidA, confirmedAdoption = true)
        api.nextResult = BackupUploadResult.Unavailable

        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        val result = repository().backupNow(currentUid = uidA, confirmedAdoption = false)
        val templateId = workoutRepository().addTemplate(programId, "Treino", "T", 0)

        assertEquals(BackupOperation.Unavailable, result)
        assertEquals("o vínculo sobrevive à indisponibilidade", uidA, database.cloudDataBindingDao().get()?.ownerUid)
        assertNotNull("o núcleo continua escrevendo", database.workoutDao().getTemplateById(templateId))
    }

    // --------------------------------------------------------------------------- helpers

    private fun scopeProvider() = CloudDataBindingScopeProvider(database.cloudDataBindingDao())

    private fun workoutRepository() = WorkoutRepository(
        database.workoutDao(),
        syncMutations = SyncMutationCoordinator(
            transactions = RoomTransactionRunner(database),
            outboxDao = database.syncOutboxDao(),
            scopeProvider = scopeProvider()
        )
    )

    private fun repository(idGenerator: IdGenerator = IdGenerator { java.util.UUID.randomUUID().toString() }) =
        BackupRepository(
            bindingDao = database.cloudDataBindingDao(),
            attemptDao = database.backupAttemptDao(),
            outboxDao = database.syncOutboxDao(),
            snapshotBuilder = BackupSnapshotBuilder(
                workoutDao = database.workoutDao(),
                bodyMeasurementDao = database.bodyMeasurementDao(),
                weeklyGoalDao = database.weeklyGoalDao(),
                aggregates = SyncAggregateSnapshotBuilder(
                    database.workoutDao(),
                    database.bodyMeasurementDao()
                )
            ),
            api = api,
            settingsManager = SettingsManager(context),
            deviceIdProvider = DeviceIdProvider(SettingsManager(context)),
            transactions = RoomTransactionRunner(database),
            source = BackupSourceDto(appVersionName = "teste", appVersionCode = 1, databaseVersion = 32),
            idGenerator = idGenerator,
            clock = { 1_700_000_000_000L }
        )
}
