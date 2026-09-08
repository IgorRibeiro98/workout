package com.example.domain.social

/**
 * A fronteira do grafo social com o Spark Backend (T17.1).
 *
 * ```text
 * UI → FriendsViewModel → FriendGateway → Spark Backend
 *                                              │
 *                                         autoridade
 * ```
 *
 * ## Por que separada do [SocialGateway]
 *
 * Elas falam com o mesmo servidor e não são a mesma coisa: uma responde "quem eu sou no social"
 * (identidade e privacidade, T17.0), a outra "com quem eu me relaciono" (T17.1). Elas mudam por
 * razões diferentes, aparecem em telas diferentes, e uma conta pode ter identidade social sem ter
 * nenhuma relação. Juntá-las produziria uma interface de treze métodos cujo primeiro terço nunca
 * é usado junto com o resto.
 *
 * ## O que esta fronteira nunca faz
 *
 * - **não escreve no Room.** Não existe `FriendshipEntity`, DAO ou transação atrás dela. Uma
 *   amizade é um fato sobre duas contas, e um banco local não é autoridade sobre isso;
 * - **não registra mutação na Outbox.** Offline, adicionar/aceitar/recusar/cancelar/remover
 *   **não acontece** — não fica pendente e não é reenviado depois. A tela diz isso;
 * - **não retenta sozinha.** Uma ação explícita do usuário produz no máximo uma requisição;
 * - **não guarda o resultado.** Quem quiser lembrar a última lista guarda em memória, ciente de
 *   que é cache e não autoridade — e a invalida ao trocar de conta.
 */
interface FriendGateway {

    /** `true` quando existe endereço de Spark Backend neste build. Sem ele, nada é oferecido. */
    val isConfigured: Boolean

    /**
     * Resolve um código de amigo.
     *
     * O texto vai como o usuário digitou: quem normaliza e decide é o servidor. Consultar **não**
     * envia pedido nenhum — é isso que impede um erro de digitação de virar convite.
     */
    suspend fun lookup(friendCode: String): FriendOutcome<FriendLookup>

    /** Envia um pedido para um `socialId`. Idempotente no servidor. */
    suspend fun sendRequest(socialId: String): FriendOutcome<FriendRequestSent>

    /** Aceita um pedido recebido. Só o destinatário consegue; o servidor verifica. */
    suspend fun acceptRequest(requestId: String): FriendOutcome<Friend>

    /** Recusa um pedido recebido. */
    suspend fun rejectRequest(requestId: String): FriendOutcome<Unit>

    /** Cancela um pedido que **eu** enviei. */
    suspend fun cancelRequest(requestId: String): FriendOutcome<Unit>

    /** Meus amigos. */
    suspend fun friends(cursor: String? = null): FriendOutcome<FriendPage<Friend>>

    /** Pedidos que recebi e ainda não respondi. */
    suspend fun incomingRequests(cursor: String? = null): FriendOutcome<FriendPage<FriendRequest>>

    /** Pedidos que enviei e ainda não foram respondidos. */
    suspend fun outgoingRequests(cursor: String? = null): FriendOutcome<FriendPage<FriendRequest>>

    /**
     * Desfaz a amizade.
     *
     * Não bloqueia, não apaga treino, histórico, backup nem sincronização, e não impede uma nova
     * amizade depois.
     */
    suspend fun removeFriend(socialId: String): FriendOutcome<Unit>
}
