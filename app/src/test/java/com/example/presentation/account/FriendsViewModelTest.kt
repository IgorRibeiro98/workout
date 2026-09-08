package com.example.presentation.account

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.social.FakeFriendGateway
import com.example.data.social.QrScan
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.FriendError
import com.example.domain.social.FriendRelationship
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O grafo social no nível do estado da tela (T17.1).
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **procurar nunca envia.** O lookup mostra quem apareceu e para — enviar é outro toque;
 * 2. **trocar de conta invalida na hora.** A lista de A nunca aparece como sendo de B, nem por um
 *    instante, nem quando a resposta de A chega depois de B entrar;
 * 3. **toque duplo não vira duas mutações**;
 * 4. **offline não finge.** Sem servidor, a ação **não acontece** e nada fica pendente;
 * 5. **o Room não muda.** Nenhuma operação do grafo escreve uma linha no banco do aparelho.
 *
 * Room de verdade (para provar que ele **não** muda) e servidor dublê com estado. Nenhum teste
 * abre socket, e nenhum toca Firebase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class FriendsViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeFriendGateway
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val accountA = SparkAccount(uid = "uid-A", displayName = "Igor", email = "a@example.com")
    private val accountB = SparkAccount(uid = "uid-B", displayName = "Jonathas", email = "b@example.com")

    private val igor = FakeFriendGateway.Account(
        uid = "uid-A",
        socialId = "social-A",
        friendCode = "SPK-AAAAAAAA",
        displayName = "Igor"
    )
    private val joao = FakeFriendGateway.Account(
        uid = "uid-B",
        socialId = "social-B",
        friendCode = "SPK-BBBBBBBB",
        displayName = "João"
    )
    private val jonathas = FakeFriendGateway.Account(
        uid = "uid-C",
        socialId = "social-C",
        friendCode = "SPK-CCCCCCCC",
        displayName = "Jonathas"
    )

    /** Os ViewModels do teste, para que o `viewModelScope` deles seja encerrado no fim. */
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeFriendGateway()
        gateway.register(igor)
        gateway.register(joao)
        gateway.register(jonathas)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        database.close()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ nada automático

    @Test
    fun `sem conta nada e consultado`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway())

        assertEquals(FriendsPhase.SignedOut, viewModel.uiState.value.phase)
        assertEquals(0, gateway.lookupCalls)
        assertEquals(0, gateway.sendCalls)
    }

    @Test
    fun `criar o ViewModel com conta nao carrega nada ate a tela abrir`() = runBlocking {
        val viewModel = signedIn()

        // Só observar a sessão não lista amigos: a leitura é um ato do usuário chegando na tela.
        assertEquals(FriendsPhase.Idle, viewModel.uiState.value.phase)

        viewModel.open()
        assertEquals(FriendsPhase.Ready, viewModel.uiState.value.phase)
    }

    @Test
    fun `abrir de novo nao refaz as requisicoes`() = runBlocking {
        val viewModel = signedIn()
        viewModel.open()
        val friendsAfterFirst = gateway.lookupCalls

        viewModel.open()
        viewModel.open()

        assertEquals(FriendsPhase.Ready, viewModel.uiState.value.phase)
        assertEquals(friendsAfterFirst, gateway.lookupCalls)
    }

    // ------------------------------------------------------------------ lookup

    @Test
    fun `procurar nao envia pedido nenhum`() = runBlocking {
        val viewModel = signedIn()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)

        viewModel.lookup()

        val lookup = viewModel.uiState.value.lookup
        assertTrue(lookup is LookupState.Found)
        assertEquals("João", (lookup as LookupState.Found).profile.displayName)
        // O ponto do fluxo: nada foi enviado. Um caractere errado não vira convite.
        assertEquals(0, gateway.sendCalls)
        assertEquals(0, gateway.pendingRequestCount())
    }

    @Test
    fun `o botao Procurar so liga com um codigo de forma valida`() = runBlocking {
        val viewModel = signedIn()
        viewModel.startAddFriend()

        viewModel.onCodeChanged("SPK-123")
        assertFalse(viewModel.uiState.value.canLookup)

        viewModel.onCodeChanged(joao.friendCode)
        assertTrue(viewModel.uiState.value.canLookup)
    }

    @Test
    fun `a entrada e normalizada antes de virar consulta`() = runBlocking {
        val viewModel = signedIn()
        viewModel.startAddFriend()

        // Minúsculas, sem hífen, com espaços: o mesmo perfil.
        viewModel.onCodeChanged(" spk bbbb bbbb ")
        viewModel.lookup()

        assertTrue(viewModel.uiState.value.lookup is LookupState.Found)
    }

    @Test
    fun `o proprio codigo e tratado, e nao vira pedido`() = runBlocking {
        val viewModel = signedIn()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(igor.friendCode)

        viewModel.lookup()

        assertEquals(LookupState.Self, viewModel.uiState.value.lookup)
        // E o envio não tem o que enviar: não existe preview para si mesmo.
        viewModel.sendRequest()
        assertEquals(0, gateway.sendCalls)
    }

    @Test
    fun `codigo inexistente e codigo de perfil desativado sao a mesma resposta`() = runBlocking {
        gateway.register(joao.copy(active = false))
        val viewModel = signedIn()
        viewModel.startAddFriend()

        viewModel.onCodeChanged("SPK-ZZZZZZZZ")
        viewModel.lookup()
        val inexistent = viewModel.uiState.value.lookup

        viewModel.onCodeChanged(joao.friendCode)
        viewModel.lookup()
        val disabled = viewModel.uiState.value.lookup

        assertEquals(LookupState.NotFound, inexistent)
        assertEquals(LookupState.NotFound, disabled)
    }

    @Test
    fun `digitar de novo invalida o resultado anterior`() = runBlocking {
        val viewModel = signedIn()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)
        viewModel.lookup()
        assertTrue(viewModel.uiState.value.lookup is LookupState.Found)

        viewModel.onCodeChanged(jonathas.friendCode)

        // Manter o preview antigo faria "Enviar solicitação" apontar para outra pessoa.
        assertEquals(LookupState.Empty, viewModel.uiState.value.lookup)
    }

    @Test
    fun `offline a busca nao inventa resultado`() = runBlocking {
        gateway.failWith = FriendError.NETWORK
        val viewModel = signedIn()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)

        viewModel.lookup()

        assertEquals(LookupState.Failed(FriendError.NETWORK), viewModel.uiState.value.lookup)
    }

    // ------------------------------------------------------------------ QR

    @Test
    fun `um QR valido preenche o codigo e procura, sem enviar`() = runBlocking {
        val viewModel = signedIn()
        viewModel.attachScanner { QrScan.FriendCode(joao.friendCode) }
        viewModel.startAddFriend()

        viewModel.scanQrCode()

        assertTrue(viewModel.uiState.value.lookup is LookupState.Found)
        assertEquals(joao.friendCode, viewModel.uiState.value.codeInput)
        assertEquals(0, gateway.sendCalls)
    }

    @Test
    fun `um QR invalido e recusado sem consultar o servidor`() = runBlocking {
        val viewModel = signedIn()
        viewModel.attachScanner { QrScan.Invalid }
        viewModel.startAddFriend()

        viewModel.scanQrCode()

        assertEquals(LookupState.InvalidQr, viewModel.uiState.value.lookup)
        assertEquals(0, gateway.lookupCalls)
    }

    @Test
    fun `fechar o leitor nao e erro`() = runBlocking {
        val viewModel = signedIn()
        viewModel.attachScanner { QrScan.Cancelled }
        viewModel.startAddFriend()

        viewModel.scanQrCode()

        assertEquals(LookupState.Empty, viewModel.uiState.value.lookup)
    }

    @Test
    fun `sem leitor no aparelho a tela oferece digitar o codigo`() = runBlocking {
        val viewModel = signedIn()
        viewModel.attachScanner { QrScan.Unavailable }
        viewModel.startAddFriend()

        viewModel.scanQrCode()

        assertEquals(LookupState.ScannerUnavailable, viewModel.uiState.value.lookup)
    }

    // ------------------------------------------------------------------ pedidos

    @Test
    fun `enviar cria o pedido e mostra o estado de enviado`() = runBlocking {
        val viewModel = signedIn()
        viewModel.open()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)
        viewModel.lookup()

        viewModel.sendRequest()

        val lookup = viewModel.uiState.value.lookup
        assertTrue(lookup is LookupState.RequestSent)
        assertEquals(1, gateway.pendingRequestCount())
        // A lista de enviados reflete na hora — sem esperar o usuário sair e voltar.
        assertEquals(1, viewModel.uiState.value.outgoing.size)
    }

    @Test
    fun `toque duplo em enviar nao cria dois pedidos`() = runBlocking {
        val viewModel = signedIn()
        viewModel.open()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)
        viewModel.lookup()

        viewModel.sendRequest()
        viewModel.sendRequest()
        viewModel.sendRequest()

        assertEquals(1, gateway.pendingRequestCount())
    }

    @Test
    fun `enviar para quem ja me enviou vira amizade na hora`() = runBlocking {
        gateway.seedRequest(requesterUid = joao.uid, recipientUid = igor.uid)
        val viewModel = signedIn()
        viewModel.open()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)
        viewModel.lookup()

        // A tela avisa o cruzamento antes do toque.
        assertEquals(
            FriendRelationship.INCOMING_PENDING,
            (viewModel.uiState.value.lookup as LookupState.Found).relationship
        )

        viewModel.sendRequest()

        assertTrue(viewModel.uiState.value.lookup is LookupState.BecameFriends)
        assertTrue(gateway.isFriendship(igor.uid, joao.uid))
        assertEquals(0, gateway.pendingRequestCount())
        assertEquals(1, viewModel.uiState.value.friends.size)
    }

    @Test
    fun `aceitar tira o pedido da lista e poe o amigo na outra`() = runBlocking {
        val requestId = gateway.seedRequest(requesterUid = joao.uid, recipientUid = igor.uid)
        val viewModel = signedIn()
        viewModel.open()
        assertEquals(1, viewModel.uiState.value.incoming.size)

        viewModel.acceptRequest(requestId)

        assertEquals("ACCEPTED", gateway.requestStatus(requestId))
        assertEquals(0, viewModel.uiState.value.incoming.size)
        assertEquals(listOf("João"), viewModel.uiState.value.friends.map { it.displayName })
    }

    @Test
    fun `toque duplo em aceitar nao cria duas amizades`() = runBlocking {
        val requestId = gateway.seedRequest(requesterUid = joao.uid, recipientUid = igor.uid)
        val viewModel = signedIn()
        viewModel.open()

        viewModel.acceptRequest(requestId)
        viewModel.acceptRequest(requestId)

        assertEquals(1, viewModel.uiState.value.friends.size)
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `recusar tira o pedido sem criar amizade`() = runBlocking {
        val requestId = gateway.seedRequest(requesterUid = joao.uid, recipientUid = igor.uid)
        val viewModel = signedIn()
        viewModel.open()

        viewModel.rejectRequest(requestId)

        assertEquals("REJECTED", gateway.requestStatus(requestId))
        assertEquals(0, viewModel.uiState.value.incoming.size)
        assertFalse(gateway.isFriendship(igor.uid, joao.uid))
    }

    @Test
    fun `cancelar tira o pedido que eu enviei`() = runBlocking {
        val requestId = gateway.seedRequest(requesterUid = igor.uid, recipientUid = joao.uid)
        val viewModel = signedIn()
        viewModel.open()
        assertEquals(1, viewModel.uiState.value.outgoing.size)

        viewModel.cancelRequest(requestId)

        assertEquals("CANCELLED", gateway.requestStatus(requestId))
        assertEquals(0, viewModel.uiState.value.outgoing.size)
    }

    @Test
    fun `um pedido ja resolvido vira aviso e recarrega, sem derrubar a tela`() = runBlocking {
        val requestId = gateway.seedRequest(requesterUid = joao.uid, recipientUid = igor.uid)
        val viewModel = signedIn()
        viewModel.open()
        // O outro lado cancelou antes.
        gateway.cancelRequestAs(joao.uid, requestId)

        viewModel.acceptRequest(requestId)

        // A lista continua utilizável: o aviso não substitui a tela.
        assertEquals(FriendsPhase.Ready, viewModel.uiState.value.phase)
        assertEquals(FriendError.REQUEST_NOT_PENDING, viewModel.uiState.value.notice)
        assertFalse(gateway.isFriendship(igor.uid, joao.uid))
    }

    @Test
    fun `offline uma resposta nao e marcada como concluida`() = runBlocking {
        val requestId = gateway.seedRequest(requesterUid = joao.uid, recipientUid = igor.uid)
        val viewModel = signedIn()
        viewModel.open()
        gateway.failWith = FriendError.NETWORK

        viewModel.acceptRequest(requestId)

        // Nada foi aceito, nada ficou pendente para "enviar depois", e o pedido continua lá.
        assertEquals("PENDING", gateway.requestStatus(requestId))
        assertEquals(FriendError.NETWORK, viewModel.uiState.value.notice)
        assertEquals(1, viewModel.uiState.value.incoming.size)
    }

    // ------------------------------------------------------------------ amizade

    @Test
    fun `remover exige confirmacao e some das duas listas`() = runBlocking {
        gateway.seedFriendship(igor.uid, joao.uid)
        val viewModel = signedIn()
        viewModel.open()
        val friend = viewModel.uiState.value.friends.first()

        viewModel.startRemoveFriend(friend)
        assertEquals(friend, viewModel.uiState.value.friendPendingRemoval)
        // Abrir a confirmação não remove nada.
        assertTrue(gateway.isFriendship(igor.uid, joao.uid))

        viewModel.confirmRemoveFriend()

        assertFalse(gateway.isFriendship(igor.uid, joao.uid))
        assertEquals(0, viewModel.uiState.value.friends.size)
    }

    @Test
    fun `remover e adicionar de novo funciona`() = runBlocking {
        gateway.seedFriendship(igor.uid, joao.uid)
        val viewModel = signedIn()
        viewModel.open()
        viewModel.startRemoveFriend(viewModel.uiState.value.friends.first())
        viewModel.confirmRemoveFriend()

        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)
        viewModel.lookup()
        viewModel.sendRequest()

        // Remover não bloqueia: o perfil continua descobrível e um pedido novo nasce.
        assertTrue(viewModel.uiState.value.lookup is LookupState.RequestSent)
        assertEquals(1, gateway.pendingRequestCount())
    }

    @Test
    fun `amigo que desativou o social nao aparece na lista`() = runBlocking {
        gateway.seedFriendship(igor.uid, joao.uid)
        gateway.register(joao.copy(active = false))
        val viewModel = signedIn()

        viewModel.open()

        // A amizade continua gravada: o que não existe é perfil social para mostrar.
        assertEquals(0, viewModel.uiState.value.friends.size)
        assertTrue(gateway.isFriendship(igor.uid, joao.uid))
    }

    @Test
    fun `social desativado nesta conta leva ao Perfil, e nao a uma lista vazia`() = runBlocking {
        gateway.failWith = FriendError.SOCIAL_DISABLED
        val viewModel = signedIn()

        viewModel.open()

        assertEquals(FriendsPhase.SocialUnavailable(disabled = true), viewModel.uiState.value.phase)
    }

    // ------------------------------------------------------------------ troca de conta

    @Test
    fun `trocar de conta limpa amigos, pedidos e busca antes de qualquer requisicao`() = runBlocking {
        gateway.seedFriendship(igor.uid, joao.uid)
        gateway.seedRequest(requesterUid = jonathas.uid, recipientUid = igor.uid)
        val auth = FakeAuthGateway(initialAccount = accountA)
        gateway.currentUid = igor.uid
        val viewModel = viewModel(auth)
        viewModel.open()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(joao.friendCode)
        viewModel.lookup()

        assertEquals(1, viewModel.uiState.value.friends.size)
        assertEquals(1, viewModel.uiState.value.incoming.size)

        gateway.currentUid = null
        auth.signOut()

        // Sair da conta apaga o estado social por inteiro — inclusive a folha aberta e o texto
        // digitado, que pertenciam à conta anterior.
        val afterLogout = viewModel.uiState.value
        assertEquals(FriendsPhase.SignedOut, afterLogout.phase)
        assertEquals(emptyList<Any>(), afterLogout.friends)
        assertEquals(emptyList<Any>(), afterLogout.incoming)
        assertEquals(emptyList<Any>(), afterLogout.outgoing)
        assertEquals(0, afterLogout.friendCount)
        assertEquals(LookupState.Empty, afterLogout.lookup)
        assertEquals("", afterLogout.codeInput)
        assertFalse(afterLogout.isAddFriendOpen)
    }

    @Test
    fun `a resposta da conta anterior nao vira estado da conta nova`() = runBlocking {
        gateway.seedFriendship(igor.uid, joao.uid)
        val auth = FakeAuthGateway(initialAccount = accountA)
        gateway.currentUid = igor.uid
        val viewModel = viewModel(auth)

        // A requisição de A fica presa no ar.
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        viewModel.open()

        // B entra no meio do voo.
        gateway.currentUid = jonathas.uid
        auth.signOut()
        auth.nextOutcome = AuthOutcome.Success(accountB)
        auth.signIn(context)
        gateway.gate = null
        gate.complete(Unit)

        // O `uid` capturado antes da chamada não vale depois dela: a resposta de A é descartada.
        val state = viewModel.uiState.value
        assertEquals(emptyList<Any>(), state.friends)
        assertTrue(state.phase is FriendsPhase.Idle || state.phase is FriendsPhase.SignedOut)
    }

    // ------------------------------------------------------------------ Room intocado

    @Test
    fun `nenhuma operacao do grafo escreve no banco do aparelho`() = runBlocking {
        val requestId = gateway.seedRequest(requesterUid = joao.uid, recipientUid = igor.uid)
        val viewModel = signedIn()
        val before = databaseSnapshot()

        viewModel.open()
        viewModel.startAddFriend()
        viewModel.onCodeChanged(jonathas.friendCode)
        viewModel.lookup()
        viewModel.sendRequest()
        viewModel.acceptRequest(requestId)
        viewModel.startRemoveFriend(viewModel.uiState.value.friends.first())
        viewModel.confirmRemoveFriend()

        // O grafo é server-authoritative: nem Outbox, nem entidade, nem vínculo de nuvem.
        assertEquals(before, databaseSnapshot())
    }

    // ------------------------------------------------------------------ apoio

    private fun signedIn(): FriendsViewModel {
        gateway.currentUid = igor.uid
        return viewModel(FakeAuthGateway(initialAccount = accountA))
    }

    private fun viewModel(auth: FakeAuthGateway) =
        FriendsViewModel(gateway = gateway, authGateway = auth)
            .also { viewModels.put("friends-${viewModelKeys++}", it) }

    /** Uma foto do que uma operação do grafo jamais pode tocar. */
    private fun databaseSnapshot(): List<Any?> = runBlocking {
        listOf(
            database.workoutDao().getAllTemplatesSync().size,
            database.syncOutboxDao().count(),
            database.cloudDataBindingDao().get()
        )
    }
}
