package com.example.presentation.account

import com.example.data.social.QrScan
import com.example.domain.social.Friend
import com.example.domain.social.FriendError
import com.example.domain.social.FriendRelationship
import com.example.domain.social.FriendRequest
import com.example.domain.social.SocialProfilePreview

/**
 * O estado do grafo social na tela (T17.1).
 *
 * ## Por que não um `isLoading`
 *
 * Porque as esperas desta tela **não são a mesma espera**, e a diferença é visível: enquanto a
 * lista carrega, os botões continuam existindo; enquanto um pedido está sendo aceito, é **aquele**
 * item que fica ocupado — e não a tela inteira. Um booleano só ofereceria duas escolhas ruins:
 * travar tudo a cada toque, ou não travar nada e deixar o toque duplo virar duas mutações.
 *
 * Por isso a ocupação é modelada por **alvo**: [pendingRequestIds] e [pendingFriendIds] dizem
 * quais linhas estão em operação, e [action] diz o que a tela inteira está fazendo.
 */
sealed interface FriendsPhase {

    /** Não há endereço de Spark Backend neste build. */
    data object NotConfigured : FriendsPhase

    /** Sem Conta Spark. Social exige uma; o resto do Spark, não. */
    data object SignedOut : FriendsPhase

    /** Nada foi pedido ainda. É o estado de quem nunca abriu a seção. */
    data object Idle : FriendsPhase

    /** A primeira leitura está em andamento. */
    data object Loading : FriendsPhase

    /** Carregado. As listas em [FriendsUiState] valem. */
    data object Ready : FriendsPhase

    /**
     * A conta não tem perfil social (ou ele está desativado).
     *
     * Estado próprio: o conselho certo aqui é "ative os recursos sociais no Perfil", que não é
     * nem erro nem lista vazia.
     */
    data class SocialUnavailable(val disabled: Boolean) : FriendsPhase

    /** Sem internet. A mensagem que importa é que **nada foi enviado**. */
    data object Offline : FriendsPhase

    /** Falhou por outra razão. [reason] é classe de erro, nunca mensagem do servidor. */
    data class Error(val reason: FriendError) : FriendsPhase
}

/**
 * O que está acontecendo agora, quando é a tela inteira.
 *
 * Ações por item (aceitar, recusar, cancelar, remover) não entram aqui: elas ficam em
 * [FriendsUiState.pendingRequestIds] e [FriendsUiState.pendingFriendIds], porque aceitar o pedido
 * do Igor não deveria bloquear a resposta ao do João.
 */
enum class FriendsAction {
    NONE,
    LOOKING_UP,
    SENDING_REQUEST,
    SCANNING
}

/** O resultado de uma busca por código, como a tela o mostra. */
sealed interface LookupState {

    /** Nada buscado ainda. */
    data object Empty : LookupState

    /** Encontrado — e ainda **não** foi enviado nada. O envio exige outro toque. */
    data class Found(
        val profile: SocialProfilePreview,
        val relationship: FriendRelationship,
        val canSendFriendRequest: Boolean
    ) : LookupState

    /** O código digitado é o do próprio usuário. */
    data object Self : LookupState

    /** Código inexistente, malformado ou de um perfil desativado — os três são iguais aqui. */
    data object NotFound : LookupState

    /** O QR lido não é um convite do Spark. */
    data object InvalidQr : LookupState

    /** O leitor de QR não está disponível neste aparelho. Digitar o código continua funcionando. */
    data object ScannerUnavailable : LookupState

    /** O pedido foi enviado. [requestId] permite cancelar sem sair da tela. */
    data class RequestSent(val profile: SocialProfilePreview, val requestId: String) : LookupState

    /** O envio cruzou com um pedido da outra pessoa e a amizade nasceu na hora. */
    data class BecameFriends(val friend: Friend) : LookupState

    /** O envio falhou. [reason] decide o texto — "essa pessoa não aceita pedidos", por exemplo. */
    data class Failed(val reason: FriendError) : LookupState
}

/**
 * O que a UI do grafo social precisa saber.
 *
 * Tudo aqui é **cache de leitura**, não fonte de verdade — a autoridade é o Spark Backend. Vive só
 * em memória, dentro do ViewModel, e é descartado na troca de conta: nada disso é gravado no Room,
 * no DataStore ou em arquivo.
 */
data class FriendsUiState(
    val phase: FriendsPhase = FriendsPhase.Idle,
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    /** Totais do servidor — não o tamanho da página. É o que a seção do Perfil mostra. */
    val friendCount: Int = 0,
    val incomingCount: Int = 0,
    val action: FriendsAction = FriendsAction.NONE,
    val lookup: LookupState = LookupState.Empty,
    /** O que o usuário digitou no campo de código. Estado de formulário, não de domínio. */
    val codeInput: String = "",
    val isCodeAcceptable: Boolean = false,
    /** `true` enquanto a folha "Adicionar amigo" está aberta. */
    val isAddFriendOpen: Boolean = false,
    /** Pedidos com uma mutação em voo. Enquanto o id estiver aqui, os botões dele não respondem. */
    val pendingRequestIds: Set<String> = emptySet(),
    /** Amizades com uma remoção em voo. */
    val pendingFriendIds: Set<String> = emptySet(),
    /** O amigo que a confirmação de remoção está mostrando. */
    val friendPendingRemoval: Friend? = null,
    /**
     * Um aviso pontual sobre a última ação — "esse pedido já tinha sido respondido", por exemplo.
     *
     * Separado de [FriendsPhase.Error] porque ele **não** substitui a tela: a lista continua
     * visível e utilizável, e o aviso desaparece na próxima ação.
     */
    val notice: FriendError? = null
) {

    /** A tela está esperando alguma coisa que a bloqueia por inteiro? */
    val isBusy: Boolean
        get() = phase is FriendsPhase.Loading || action != FriendsAction.NONE

    /** O código lido/digitado já pode ser procurado? */
    val canLookup: Boolean
        get() = isCodeAcceptable && action == FriendsAction.NONE

    fun isRequestBusy(requestId: String): Boolean = requestId in pendingRequestIds

    fun isFriendBusy(socialId: String): Boolean = socialId in pendingFriendIds
}

/** O desfecho de uma leitura de QR, traduzido para o estado da tela. */
fun QrScan.toLookupState(): LookupState? = when (this) {
    is QrScan.FriendCode -> null
    QrScan.Invalid -> LookupState.InvalidQr
    QrScan.Cancelled -> LookupState.Empty
    QrScan.Unavailable -> LookupState.ScannerUnavailable
}
