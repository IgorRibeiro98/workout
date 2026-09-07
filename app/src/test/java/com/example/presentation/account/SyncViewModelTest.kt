package com.example.presentation.account

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.sync.CloudSyncState
import com.example.data.sync.FakeSparkSyncServer
import com.example.data.sync.SyncAccountProvider
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncOutcome
import com.example.data.sync.SyncDevice
import com.example.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A sincronização no nível do estado da tela (T16.6).
 *
 * O que estes testes protegem, acima de tudo: **nada acontece sozinho**. Abrir o Perfil, entrar na
 * conta e observar o estado não podem sincronizar — só um toque explícito, o app voltando ao
 * primeiro plano ou o trabalho agendado podem.
 *
 * E o segundo: a tela fala português, não protocolo. `cursor`, `revision` e `serverSequence` não
 * atravessam esta fronteira; o que atravessa é "atualizado", "aguardando envio" e "precisa de
 * atenção".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncViewModelTest {

    private val ownerUid = "uid-A"

    private lateinit var server: FakeSparkSyncServer
    private lateinit var device: SyncDevice
    private lateinit var scheduler: RecordingScheduler
    private var currentUid: String? = ownerUid

    /**
     * O trabalho lançado pelo coordenador, sob controle do teste.
     *
     * `syncNow()` e `onAppForeground()` **lançam** corrotinas e voltam na hora — é assim que o app
     * funciona. Sem um `Job` para cancelar e esperar, uma delas pode ainda estar consultando o Room
     * quando o teste termina; fechar o banco embaixo dela lança uma exceção não capturada, e o
     * `kotlinx-coroutines-test` a reporta na **próxima** classe de teste — um problema local vira
     * falha em outra suíte, sem relação aparente.
     */
    private lateinit var coordinatorJob: Job
    private lateinit var coordinatorScope: CoroutineScope

    /** Os ViewModels do teste, para que `viewModelScope` seja encerrado antes do banco fechar. */
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setUp() {
        // Dispatcher real, e não virtual: o Room roda nos executores dele, e um relógio virtual
        // não faz o banco responder mais cedo. O que sincroniza o teste é esperar o **estado**.
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = FakeSparkSyncServer()
        device = SyncDevice(server, ownerUid, "device-a")
        scheduler = RecordingScheduler()
        currentUid = ownerUid
        coordinatorJob = SupervisorJob()
        coordinatorScope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined)
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        // A ordem é o ponto: primeiro encerra quem pode estar falando com o banco, depois fecha o
        // banco. `clear()` cancela o `viewModelScope`; `cancelAndJoin` espera o trabalho do
        // coordenador realmente terminar em vez de só pedir para parar.
        viewModelStore.clear()
        runBlocking { withTimeout(5_000) { coordinatorJob.cancelAndJoin() } }
        device.close()
        Dispatchers.resetMain()
    }

    private fun coordinator() = SyncCoordinator(
        repository = device.repository,
        accounts = SyncAccountProvider { currentUid },
        scheduler = scheduler,
        scope = coordinatorScope,
        // O mesmo relógio do repositório: "faz quanto tempo que sincronizei?" só faz sentido se
        // os dois lados medirem o tempo da mesma origem.
        clock = { SyncDevice.CLOCK }
    )

    private fun viewModel(): SyncViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SyncViewModel(device.repository, coordinator()) as T
        }
        val model = ViewModelProvider(viewModelStore, factory)[SyncViewModel::class.java]
        model.onAccountChanged(currentUid)
        return model
    }

    private suspend fun awaitPhase(
        model: SyncViewModel,
        predicate: (SyncPhase) -> Boolean
    ): SyncPhase = withTimeout(5_000) {
        var phase = model.uiState.value.phase
        while (!predicate(phase)) {
            kotlinx.coroutines.yield()
            phase = model.uiState.value.phase
        }
        phase
    }

    // ------------------------------------------------------------------- nada automático

    @Test
    fun `criar o ViewModel nao sincroniza`() = runBlocking {
        device.bind()
        device.newTemplate("Treino A")

        viewModel()

        assertEquals("abrir a tela não fala com o servidor", 0, device.api.pushCalls)
        assertEquals(0, device.api.pullCalls)
        assertEquals(1, device.pendingCount())
    }

    @Test
    fun `dataset sem vinculo mostra sync desligado e nao oferece acao remota`() = runBlocking {
        val model = viewModel()

        assertEquals(SyncPhase.Disabled, awaitPhase(model) { it is SyncPhase.Disabled })

        // Tocar em sincronizar num dataset sem dono não manda nada: login não liga a nuvem.
        model.syncNow()
        assertEquals(0, device.api.pushCalls)
    }

    @Test
    fun `sem sessao a tela pede para entrar`() = runBlocking {
        device.bind()
        currentUid = null

        val model = viewModel()

        assertEquals(SyncPhase.AuthRequired, awaitPhase(model) { it is SyncPhase.AuthRequired })
    }

    @Test
    fun `conta diferente do vinculo vira descompasso`() = runBlocking {
        device.bind()
        currentUid = "uid-de-outra-conta"

        val model = viewModel()

        assertEquals(
            SyncPhase.AccountMismatch,
            awaitPhase(model) { it is SyncPhase.AccountMismatch }
        )
    }

    @Test
    fun `adocao em andamento ainda nao sincroniza`() = runBlocking {
        device.bind(CloudSyncState.PREPARING)
        device.newTemplate("Treino A")

        // O vínculo existe, mas o primeiro backup não foi confirmado: não há baseline no servidor.
        // Sem coordenador no meio, para medir o ciclo e não uma corrida com ele.
        assertEquals(SyncOutcome.NotEnabled, device.repository.syncNow(ownerUid))
        assertEquals(0, device.api.pushCalls)

        // E a tela mostra sync desligado, em vez de dizer que algo convergiu.
        assertEquals(null, device.repository.snapshot().ownerUid)
        val model = viewModel()
        assertEquals(SyncPhase.Disabled, awaitPhase(model) { it is SyncPhase.Disabled })
    }

    // ------------------------------------------------------------------- estados

    @Test
    fun `alteracoes pendentes aparecem contadas`() = runBlocking {
        device.bind()
        device.newTemplate("Treino A")
        device.newTemplate("Treino B")

        val model = viewModel()

        val phase = awaitPhase(model) { it is SyncPhase.Pending } as SyncPhase.Pending
        assertEquals(2, phase.pending)
    }

    @Test
    fun `depois de sincronizar a tela diz atualizado`() = runBlocking {
        device.bind()
        device.newTemplate("Treino A")
        val model = viewModel()

        model.syncNow()

        val phase = awaitPhase(model) { it is SyncPhase.UpToDate } as SyncPhase.UpToDate
        assertTrue("a última sincronização precisa ter hora", phase.lastSyncedAt != null)
        assertEquals(0, device.pendingCount())
    }

    @Test
    fun `sem rede a tela diz offline e nada e perdido`() = runBlocking {
        device.bind()
        device.newTemplate("Treino A")
        device.api.offline = true
        val model = viewModel()

        model.syncNow()

        val phase = awaitPhase(model) { it is SyncPhase.Offline } as SyncPhase.Offline
        assertEquals(1, phase.pending)
        assertEquals(1, device.pendingCount())
    }

    @Test
    fun `conflito vira um item que precisa de atencao`() = runBlocking {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        val outro = SyncDevice(server, ownerUid, "device-b")
        try {
            outro.bind()
            outro.sync()
            outro.renameTemplate(templateSyncId, "Escrito pelo outro")
            outro.sync()
        } finally {
            outro.close()
        }

        device.renameTemplate(templateSyncId, "Escrito aqui")
        val model = viewModel()
        model.syncNow()

        val phase = awaitPhase(model) { it is SyncPhase.NeedsAttention } as SyncPhase.NeedsAttention
        assertEquals(1, phase.items)
        // E a alteração local continua na tela do usuário, intacta.
        assertEquals("Escrito aqui", device.templateName(templateSyncId))

        // A tela recebe o item com as duas versões e as escolhas — sem jargão de protocolo.
        val conflict = model.uiState.value.conflicts.single()
        assertEquals("Escrito aqui", conflict.title)
        assertEquals(
            listOf(
                com.example.data.sync.SyncConflictChoice.KEEP_LOCAL,
                com.example.data.sync.SyncConflictChoice.USE_REMOTE
            ),
            conflict.choices
        )
    }

    @Test
    fun `escolher manter a versao deste aparelho converge e limpa o aviso`() = runBlocking {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        val outro = SyncDevice(server, ownerUid, "device-b")
        try {
            outro.bind()
            outro.sync()
            outro.renameTemplate(templateSyncId, "Escrito pelo outro")
            outro.sync()
        } finally {
            outro.close()
        }

        device.renameTemplate(templateSyncId, "Escrito aqui")
        val model = viewModel()
        model.syncNow()
        awaitPhase(model) { it is SyncPhase.NeedsAttention }

        val conflict = model.uiState.value.conflicts.single()
        model.resolveConflict(conflict.id, com.example.data.sync.SyncConflictChoice.KEEP_LOCAL)

        awaitPhase(model) { it is SyncPhase.UpToDate }
        assertEquals("Escrito aqui", device.templateName(templateSyncId))
        assertEquals(0, model.uiState.value.conflicts.size)
        assertEquals(null, model.uiState.value.resolutionProblem)
    }

    @Test
    fun `escolher a versao da nuvem aplica aqui e nao enfileira nada`() = runBlocking {
        device.bind()
        val templateSyncId = device.newTemplate("Treino A")
        device.sync()

        val outro = SyncDevice(server, ownerUid, "device-b")
        try {
            outro.bind()
            outro.sync()
            outro.renameTemplate(templateSyncId, "Escrito pelo outro")
            outro.sync()
        } finally {
            outro.close()
        }

        device.renameTemplate(templateSyncId, "Escrito aqui")
        val model = viewModel()
        model.syncNow()
        awaitPhase(model) { it is SyncPhase.NeedsAttention }

        val conflict = model.uiState.value.conflicts.single()
        model.resolveConflict(conflict.id, com.example.data.sync.SyncConflictChoice.USE_REMOTE)

        awaitPhase(model) { it is SyncPhase.UpToDate }
        assertEquals("Escrito pelo outro", device.templateName(templateSyncId))
        // Aplicar o remoto não devolve nada ao servidor.
        assertEquals(0, device.pendingCount())
        assertEquals(0, device.blockedCount())
    }

    // ------------------------------------------------------------------- gatilhos

    @Test
    fun `toque repetido durante um ciclo nao cria um segundo`() = runBlocking {
        device.bind()
        device.newTemplate("Treino A")
        val model = viewModel()

        model.syncNow()
        model.syncNow()
        model.syncNow()

        awaitPhase(model) { it is SyncPhase.UpToDate }
        // Três toques, um push. O coordenador recusa o segundo ciclo enquanto o primeiro roda, e
        // depois dele não há mais nada pendente para enviar.
        assertEquals(1, device.api.pushCalls)
    }

    @Test
    fun `uma alteracao local agenda um ciclo em vez de falar HTTP na hora`() = runBlocking {
        device.bind()
        val coordinator = coordinator()

        coordinator.onLocalMutation()
        coordinator.onLocalMutation()

        // Salvar um treino nunca espera o servidor: o que acontece é um agendamento.
        assertEquals(0, device.api.pushCalls)
        assertEquals(2, scheduler.scheduled)
    }

    @Test
    fun `abrir o app sincroniza quando esta desatualizado, e nao insiste logo depois`() = runBlocking {
        device.bind()
        val coordinator = coordinator()

        // Nunca sincronizado: o primeiro foreground vale.
        coordinator.onAppForeground()
        awaitUntil { device.repository.snapshot().lastSyncedAt != null }
        val afterFirst = device.api.pullCalls
        assertTrue(afterFirst > 0)

        // Logo em seguida, sem nada pendente e com a sincronização recente, não vale de novo:
        // abrir o Spark dez vezes em cinco minutos não produz dez ciclos.
        coordinator.onAppForeground()
        kotlinx.coroutines.delay(50)
        assertEquals(afterFirst, device.api.pullCalls)
    }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) kotlinx.coroutines.yield()
    }

    private class RecordingScheduler : SyncScheduler {
        var scheduled = 0
            private set

        override fun scheduleSoon() {
            scheduled++
        }
    }
}
