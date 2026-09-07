package com.example.presentation.account

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.backup.BackupRepository
import com.example.data.backup.BackupSnapshotBuilder
import com.example.data.backup.BackupSourceDto
import com.example.data.backup.BackupUploadResult
import com.example.data.backup.FakeBackupApi
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncAggregateSnapshotBuilder
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.AuthState
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * O backup no nível do estado da tela (T16.4).
 *
 * O que estes testes protegem, acima de tudo: **nada acontece sozinho**. Abrir o Perfil, entrar na
 * conta e observar o estado não podem vincular dado nem enviar nada — só um toque explícito,
 * seguido de confirmação explícita, pode.
 *
 * Room de verdade e repositório de verdade; o servidor é dublê. Nenhum teste abre socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BackupViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var api: FakeBackupApi
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val accountA = SparkAccount(uid = "uid-A", email = "atleta@example.com")
    private val accountB = SparkAccount(uid = "uid-B", email = "outro@example.com")

    @Before
    fun setUp() {
        // Dispatcher real, e não virtual: o Room roda nos executores dele, e um relógio virtual
        // não faz o banco responder mais cedo. O que sincroniza o teste é esperar o **estado**.
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = FakeBackupApi()
    }

    /**
     * Os ViewModels do teste, para que o `viewModelScope` deles seja encerrado.
     *
     * `BackupViewModel` coleta `authGateway.state` no `init`, e um `StateFlow` não termina. Sem
     * cancelar, a corrotina continua consultando o Room depois do `close()` e lendo
     * `Dispatchers.Main` durante o `resetMain` — e quem falha é a classe seguinte.
     */
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    @After
    fun tearDown() {
        // A ordem é o ponto: encerra quem ainda coleta, depois fecha o banco, depois devolve o
        // dispatcher.
        viewModels.clear()
        database.close()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------- nada automático

    @Test
    fun `sem conta a secao convida a entrar e nao envia nada`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway())

        assertEquals(BackupPhase.NotAuthenticated, viewModel.uiState.value.phase)
        assertEquals(0, api.callCount)
    }

    @Test
    fun `entrar na conta nao dispara backup`() = runBlocking {
        val gateway = FakeAuthGateway()
        val viewModel = viewModel(gateway)

        gateway.nextOutcome = AuthOutcome.Success(accountA)
        gateway.signIn(context)

        // A tela passa a oferecer a ativação, e nada mais acontece.
        assertEquals(BackupPhase.Unbound, awaitPhase(viewModel) { it is BackupPhase.Unbound })
        assertEquals("login não pode enviar dado", 0, api.callCount)
        assertNull(database.cloudDataBindingDao().get())
        assertEquals(0, database.backupAttemptDao().count())
    }

    @Test
    fun `criar o ViewModel nao vincula nem envia`() = runBlocking {
        viewModel(FakeAuthGateway(initialAccount = accountA))

        // Uma sessão restaurada não é uma decisão de adotar dados.
        assertNull(database.cloudDataBindingDao().get())
        assertEquals(0, api.callCount)
    }

    @Test
    fun `sem conta o botao de ativar nao faz nada`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway())

        viewModel.startAdoption()

        assertEquals(false, viewModel.uiState.value.isConfirmingAdoption)
        assertEquals(0, api.callCount)
        assertNull(database.cloudDataBindingDao().get())
    }

    // ------------------------------------------------------------------------- adoção

    @Test
    fun `ativar backup abre a confirmacao com o resumo, sem vincular`() = runBlocking {
        seedSomeData()
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        awaitPhase(viewModel) { it is BackupPhase.Unbound }
        viewModel.startAdoption()

        val state = viewModel.uiState.first { it.isConfirmingAdoption }
        assertTrue(state.isConfirmingAdoption)
        assertEquals("atleta@example.com", state.accountEmail)
        assertEquals(2, state.summary?.templates)
        assertEquals(1, state.summary?.programs)
        // Abrir a confirmação é leitura: nada foi vinculado e nada foi enviado.
        assertNull(database.cloudDataBindingDao().get())
        assertEquals(0, api.callCount)
    }

    @Test
    fun `cancelar a confirmacao nao vincula e nao envia`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        awaitPhase(viewModel) { it is BackupPhase.Unbound }
        viewModel.startAdoption()
        viewModel.uiState.first { it.isConfirmingAdoption }
        viewModel.cancelAdoption()

        assertEquals(false, viewModel.uiState.value.isConfirmingAdoption)
        assertNull(viewModel.uiState.value.summary)
        assertNull(database.cloudDataBindingDao().get())
        assertEquals(0, api.callCount)
        assertEquals(0, database.backupAttemptDao().count())
    }

    @Test
    fun `confirmar vincula o dataset e envia o snapshot`() = runBlocking {
        api.nextResult = FakeBackupApi.success(createdAt = 1_800_000_000_000L, itemCount = 3)
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        adopt(viewModel)

        assertEquals("uid-A", database.cloudDataBindingDao().get()?.ownerUid)
        assertEquals(1, api.callCount)

        val phase = viewModel.uiState.value.phase
        assertTrue(phase is BackupPhase.Ready)
        // A hora vem do **servidor**, não de quando o botão foi tocado.
        assertEquals(1_800_000_000_000L, (phase as BackupPhase.Ready).lastBackupAt)
        assertEquals(false, phase.hasPendingAttempt)
    }

    @Test
    fun `dez toques em fazer backup agora nao viram dez backups`() = runBlocking {
        api.nextResult = FakeBackupApi.success()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val arrived = kotlinx.coroutines.CompletableDeferred<Unit>()
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))
        adopt(viewModel)
        val afterAdoption = api.callCount

        // O servidor segura a primeira requisição; os outros nove toques chegam com ela em voo.
        api.arrived = arrived
        api.gate = gate
        repeat(10) { viewModel.backupNow() }
        arrived.await()

        assertEquals("dez toques, um upload", afterAdoption + 1, api.callCount)

        gate.complete(Unit)
        awaitPhase(viewModel) { it is BackupPhase.Ready }
        assertEquals(afterAdoption + 1, api.callCount)
        assertEquals("um backup lógico por toque, não dez", 2, database.backupAttemptDao().count())
    }

    // ------------------------------------------------------------------ troca de conta

    @Test
    fun `entrar com outra conta bloqueia a nuvem sem enviar nada`() = runBlocking {
        api.nextResult = FakeBackupApi.success()
        val gateway = FakeAuthGateway(initialAccount = accountA)
        val viewModel = viewModel(gateway)
        adopt(viewModel)
        val callsAfterAdoption = api.callCount

        gateway.signOut()
        gateway.nextOutcome = AuthOutcome.Success(accountB)
        gateway.signIn(context)

        assertEquals(
            BackupPhase.AccountMismatch,
            awaitPhase(viewModel) { it is BackupPhase.AccountMismatch }
        )

        viewModel.backupNow()
        awaitPhase(viewModel) { it is BackupPhase.AccountMismatch }

        assertEquals("nada pode subir no descompasso", callsAfterAdoption, api.callCount)
        assertEquals("uid-A", database.cloudDataBindingDao().get()?.ownerUid)
        assertEquals(BackupPhase.AccountMismatch, viewModel.uiState.value.phase)
    }

    @Test
    fun `sair da conta nao remove o vinculo`() = runBlocking {
        api.nextResult = FakeBackupApi.success()
        val gateway = FakeAuthGateway(initialAccount = accountA)
        val viewModel = viewModel(gateway)
        adopt(viewModel)

        gateway.signOut()

        assertEquals(AuthState.SignedOut, gateway.state.value)
        assertEquals(
            BackupPhase.NotAuthenticated,
            awaitPhase(viewModel) { it is BackupPhase.NotAuthenticated }
        )
        assertEquals("o vínculo é do dataset, não da sessão", "uid-A", database.cloudDataBindingDao().get()?.ownerUid)
    }

    // ------------------------------------------------------------------------- falhas

    @Test
    fun `backup falhado nao e logout`() = runBlocking {
        api.nextResult = FakeBackupApi.success()
        val gateway = FakeAuthGateway(initialAccount = accountA)
        val viewModel = viewModel(gateway)
        adopt(viewModel)

        api.nextResult = BackupUploadResult.Unavailable
        viewModel.backupNow()

        assertEquals(
            BackupPhase.Failed(BackupFailure.UNAVAILABLE),
            awaitPhase(viewModel) { it is BackupPhase.Failed }
        )
        // Quem decide se há conta é o Firebase Auth local, e ele continua dizendo que há.
        assertTrue(gateway.state.value is AuthState.SignedIn)
        assertNotNull("a tentativa continua recuperável", database.backupAttemptDao().oldestPendingFor("uid-A"))
    }

    @Test
    fun `sem backend configurado a secao nao aparece`() = runBlocking {
        api = FakeBackupApi(isConfigured = false)
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        assertEquals(BackupPhase.NotConfigured, viewModel.uiState.value.phase)
        viewModel.startAdoption()
        viewModel.backupNow()
        assertEquals(0, api.callCount)
    }

    // --------------------------------------------------------------------------- helpers

    /**
     * Espera o estado que a leitura assíncrona do banco vai produzir.
     *
     * Sem `sleep` e sem número mágico de espera: o teste aguarda a condição real e falha por
     * timeout se ela não vier.
     */
    private suspend fun awaitPhase(
        viewModel: BackupViewModel,
        predicate: (BackupPhase) -> Boolean
    ): BackupPhase = withTimeout(AWAIT_TIMEOUT_MS) {
        viewModel.uiState.first { predicate(it.phase) }.phase
    }

    /** O caminho completo da adoção: ativar, confirmar e esperar o backup terminar. */
    private suspend fun adopt(viewModel: BackupViewModel) {
        awaitPhase(viewModel) { it is BackupPhase.Unbound }
        viewModel.startAdoption()
        viewModel.uiState.first { it.isConfirmingAdoption }
        viewModel.confirmAdoption()
        awaitPhase(viewModel) { it is BackupPhase.Ready }
    }

    private suspend fun seedSomeData() {
        val dao = database.workoutDao()
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Treino A"))
        dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Treino B"))
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L
    }

    private fun viewModel(gateway: FakeAuthGateway) = buildViewModel(gateway)
        .also { viewModels.put("backup-${viewModelKeys++}", it) }

    private fun buildViewModel(gateway: FakeAuthGateway) = BackupViewModel(
        repository = BackupRepository(
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
            clock = { 1_700_000_000_000L }
        ),
        authGateway = gateway
    )
}
