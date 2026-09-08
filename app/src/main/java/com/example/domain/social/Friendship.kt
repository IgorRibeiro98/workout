package com.example.domain.social

/**
 * O grafo social do Spark, do ponto de vista do domínio (T17.1).
 *
 * ```text
 * friendCode ──lookup──▶ SocialProfilePreview ──pedido──▶ FriendRequest ──aceite──▶ Friend
 * ```
 *
 * ## Tudo aqui é **cópia lida**, nunca autoridade
 *
 * A autoridade do grafo é o Spark Backend, pelo mesmo motivo da identidade social (T17.0): uma
 * amizade é um fato sobre **duas** contas, e nenhum aparelho pode decidi-la sozinho. Por isso
 * nenhum destes tipos tem `localId`, entidade Room correspondente, `syncId` ou entrada na Outbox —
 * guardá-los criaria uma segunda verdade sobre quem é amigo de quem, e a primeira divergência
 * seria uma lista de amigos que só existe em um celular.
 *
 * ## O que uma amizade **não** dá
 *
 * Ser amigo não concede acesso a treino, histórico, medidas, backup, sincronização, e-mail ou
 * Firebase UID. Nenhum tipo deste arquivo carrega qualquer um deles, e a ausência é o contrato.
 */

/**
 * O perfil de outra pessoa, no mínimo que o Spark expõe.
 *
 * `socialId` e `displayName`, e nada mais. Não há `friendCode` aqui: depois que o código cumpriu a
 * função de descoberta, quem identifica é o `socialId` — e uma lista de contatos que carregasse o
 * código de convite de todo mundo seria uma lista redistribuível que ninguém escolheu publicar.
 */
data class SocialProfilePreview(
    val socialId: String,
    val displayName: String
)

/** Um amigo, na lista de amigos. */
data class Friend(
    val socialId: String,
    val displayName: String,
    /** Quando a amizade foi criada. Relógio do **servidor**, epoch millis UTC. */
    val friendsSince: Long
)

/** De onde o pedido veio, do ponto de vista de quem está olhando. */
enum class FriendRequestDirection {
    /** Alguém quer me adicionar. Eu aceito ou recuso. */
    INCOMING,

    /** Eu pedi. Eu posso cancelar; quem responde é a outra pessoa. */
    OUTGOING
}

/**
 * Um pedido de amizade pendente.
 *
 * [requestId] é o identificador **opaco** do servidor (UUID). Toda ação sobre o pedido usa ele —
 * nunca o nome exibido, que não é único e não identifica ninguém.
 */
data class FriendRequest(
    val requestId: String,
    val profile: SocialProfilePreview,
    val direction: FriendRequestDirection,
    /** Relógio do servidor, epoch millis UTC. */
    val createdAt: Long
)

/**
 * A relação que já existe com um perfil encontrado.
 *
 * Ela existe para a tela oferecer a ação certa de primeira — "Enviar solicitação", "Solicitação
 * enviada", "Responder" ou "Vocês já são amigos" — em vez de oferecer sempre a mesma e descobrir
 * o estado real só depois do toque.
 */
enum class FriendRelationship {
    NONE,
    FRIENDS,
    OUTGOING_PENDING,
    INCOMING_PENDING
}

/**
 * O desfecho de um lookup por código.
 *
 * Modelado com nomes, e não com `Result<SocialProfilePreview?>`: "encontrei", "esse código é o
 * seu" e "não existe" levam a **três telas diferentes**, e um nulo obrigaria a UI a adivinhar
 * qual delas.
 */
sealed interface FriendLookup {

    /** Encontrado. [canSendFriendRequest] é dica de UI — quem decide o envio é o servidor. */
    data class Found(
        val profile: SocialProfilePreview,
        val relationship: FriendRelationship,
        val canSendFriendRequest: Boolean
    ) : FriendLookup

    /** O código digitado é o do próprio usuário. */
    data object Self : FriendLookup

    /**
     * Nenhum perfil alcançável com esse código.
     *
     * Código malformado, inexistente e de perfil desativado chegam **todos** aqui, porque o
     * servidor responde a mesma coisa para os três de propósito. O app não tenta distinguir, e
     * não tenta corrigir o que foi digitado.
     */
    data object NotFound : FriendLookup
}

/** O desfecho de enviar um pedido. */
sealed interface FriendRequestSent {

    /** Nasceu um pedido pendente. */
    data class Created(val request: FriendRequest) : FriendRequestSent

    /** Já havia um pedido igual — reenvio ou toque duplo. O mesmo pedido volta. */
    data class AlreadyPending(val request: FriendRequest) : FriendRequestSent

    /**
     * Havia um pedido **inverso** pendente, e a amizade foi criada na hora.
     *
     * Os dois já tinham dito, explicitamente, que queriam ser amigos. Exigir um terceiro toque
     * não protegeria ninguém.
     */
    data class BecameFriends(val friend: Friend) : FriendRequestSent
}

/** Uma página de uma listagem: o que veio, quantos existem e como pedir o resto. */
data class FriendPage<T>(
    val items: List<T>,
    /** Quantos existem ao todo — não quantos vieram nesta página. */
    val total: Int,
    /** Continuação opaca. `null` significa que acabou. */
    val nextCursor: String? = null
)

/**
 * Por que uma operação do grafo não completou.
 *
 * Classes de falha, e não mensagens do servidor: mensagem de servidor não é texto de UI, e um
 * `code` interno não ajuda quem está olhando a tela.
 */
enum class FriendError {

    /** Não há endereço de Spark Backend neste build. Nenhuma requisição foi feita. */
    NOT_CONFIGURED,

    /** É preciso entrar na Conta Spark. */
    AUTH_REQUIRED,

    /** A conta não ativou os recursos sociais. A tela leva ao Perfil. */
    SOCIAL_NOT_ENABLED,

    /** Os recursos sociais desta conta estão desativados. Reativar no Perfil resolve. */
    SOCIAL_DISABLED,

    /** O perfil alvo não existe ou não está alcançável. */
    PROFILE_NOT_FOUND,

    /** Pedido para si mesmo. A UI já impede; isto é a defesa de trás. */
    SELF_REQUEST,

    /** A pessoa desligou o recebimento de pedidos. */
    REQUESTS_DISABLED,

    /** Vocês já são amigos — a lista precisa ser recarregada. */
    ALREADY_FRIENDS,

    /** O pedido não existe (ou não é seu). */
    REQUEST_NOT_FOUND,

    /** O pedido já foi resolvido por outro caminho — o outro lado cancelou ou respondeu antes. */
    REQUEST_NOT_PENDING,

    /** Ação de participante errado: aceitar sem ser o destinatário, cancelar sem ser o remetente. */
    NOT_ALLOWED,

    /** Não há amizade para desfazer. */
    FRIENDSHIP_NOT_FOUND,

    /** Muitas requisições para esta conta. Recuperável: esperar resolve. */
    RATE_LIMITED,

    /** O servidor recusou a requisição. Isso é defeito, e o texto convida a relatar. */
    REJECTED,

    /** O Spark Backend respondeu indisponível. Recuperável. */
    UNAVAILABLE,

    /** Sem internet ou servidor inalcançável. **Nada foi enviado**, e nada ficou pendente. */
    NETWORK
}

/** O desfecho genérico de uma operação do grafo. */
sealed interface FriendOutcome<out T> {
    data class Success<T>(val value: T) : FriendOutcome<T>
    data class Failure(val error: FriendError) : FriendOutcome<Nothing>
}
