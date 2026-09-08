package com.example.data.social

import com.example.domain.social.Friend
import com.example.domain.social.FriendError
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendLookup
import com.example.domain.social.FriendOutcome
import com.example.domain.social.FriendPage
import com.example.domain.social.FriendRelationship
import com.example.domain.social.FriendRequest
import com.example.domain.social.FriendRequestDirection
import com.example.domain.social.FriendRequestSent
import com.example.domain.social.SocialProfilePreview
import kotlinx.coroutines.CompletableDeferred

/**
 * O grafo social do Spark Backend, em memória (T17.1).
 *
 * Ele não é um mock de chamadas: é um **servidor de mentira com estado**, que reproduz as regras
 * que o servidor de verdade aplica — amizade bilateral, pedido idempotente, cruzamento que vira
 * amizade, autorização por participante e isolamento por conta. É isso que permite ao teste de
 * troca de conta e ao de toque duplo afirmarem alguma coisa: contra um mock que devolve objetos
 * fixos, os dois passariam sem provar nada.
 *
 * Vive em `test/`, e só em `test/`.
 */
class FakeFriendGateway(
    override val isConfigured: Boolean = true
) : FriendGateway {

    /** A conta autenticada, do ponto de vista deste "servidor". Trocá-la é trocar de conta. */
    var currentUid: String? = null

    /** O que a próxima operação deve responder, quando não for para funcionar. */
    var failWith: FriendError? = null

    /**
     * Quando presente, a próxima operação **espera** aqui.
     *
     * É o que permite observar o estado "no meio do voo" — em particular, provar que trocar de
     * conta durante uma requisição descarta a resposta que chegar depois.
     */
    var gate: CompletableDeferred<Unit>? = null

    /** Perfis conhecidos por `friendCode`, com o dono. */
    private val profilesByCode = mutableMapOf<String, Account>()
    private val profilesById = mutableMapOf<String, Account>()

    /** Pares canônicos de amizade e quando nasceram. */
    private val friendships = mutableMapOf<Pair<String, String>, Long>()

    private val requests = mutableListOf<StoredRequest>()

    var lookupCalls = 0
        private set
    var sendCalls = 0
        private set
    var acceptCalls = 0
        private set
    var rejectCalls = 0
        private set
    var cancelCalls = 0
        private set
    var removeCalls = 0
        private set

    private var nextRequestId = 1
    private var clock = 1_000L

    data class Account(
        val uid: String,
        val socialId: String,
        val friendCode: String,
        val displayName: String,
        val acceptsRequests: Boolean = true,
        val active: Boolean = true
    )

    private data class StoredRequest(
        val requestId: String,
        val requesterUid: String,
        val recipientUid: String,
        var status: String,
        val createdAt: Long
    )

    fun register(account: Account): Account {
        profilesByCode[account.friendCode] = account
        profilesById[account.socialId] = account
        return account
    }

    /** Cria a amizade direto, para os testes que começam com o par já formado. */
    fun seedFriendship(uidA: String, uidB: String) {
        friendships[pair(uidA, uidB)] = clock++
    }

    /** Cria um pedido pendente direto, e devolve o id. */
    fun seedRequest(requesterUid: String, recipientUid: String): String {
        val id = "req-${nextRequestId++}"
        requests += StoredRequest(id, requesterUid, recipientUid, "PENDING", clock++)
        return id
    }

    fun isFriendship(uidA: String, uidB: String): Boolean = pair(uidA, uidB) in friendships

    fun requestStatus(requestId: String): String? =
        requests.firstOrNull { it.requestId == requestId }?.status

    fun pendingRequestCount(): Int = requests.count { it.status == "PENDING" }

    /**
     * O **outro lado** resolve o pedido, fora do app que está sendo testado.
     *
     * É como se reproduz a corrida do §32: quem enviou cancela enquanto quem recebeu ainda tem a
     * tela aberta com o botão "Aceitar".
     */
    fun cancelRequestAs(uid: String, requestId: String) {
        val request = requests.firstOrNull { it.requestId == requestId } ?: return
        if (request.requesterUid == uid && request.status == "PENDING") {
            request.status = "CANCELLED"
        }
    }

    // ------------------------------------------------------------------------------ operações

    override suspend fun lookup(friendCode: String): FriendOutcome<FriendLookup> {
        lookupCalls += 1
        return respond { uid ->
            // A normalização acontece no servidor: o app manda o que foi digitado.
            val canonical = FriendshipContract.normalizeFriendCode(friendCode)
            val target = canonical?.let { profilesByCode[it] }

            when {
                // Malformado, inexistente e desativado dão a mesma resposta, como no servidor.
                target == null || !target.active -> FriendOutcome.Success(FriendLookup.NotFound)
                target.uid == uid -> FriendOutcome.Success(FriendLookup.Self)
                else -> {
                    val relationship = relationshipOf(uid, target.uid)
                    FriendOutcome.Success(
                        FriendLookup.Found(
                            profile = SocialProfilePreview(target.socialId, target.displayName),
                            relationship = relationship,
                            canSendFriendRequest = target.acceptsRequests &&
                                relationship != FriendRelationship.FRIENDS &&
                                relationship != FriendRelationship.OUTGOING_PENDING
                        )
                    )
                }
            }
        }
    }

    override suspend fun sendRequest(socialId: String): FriendOutcome<FriendRequestSent> {
        sendCalls += 1
        return respond { uid ->
            val target = profilesById[socialId]?.takeIf { it.active }
                ?: return@respond FriendOutcome.Failure(FriendError.PROFILE_NOT_FOUND)
            if (target.uid == uid) return@respond FriendOutcome.Failure(FriendError.SELF_REQUEST)
            if (isFriendship(uid, target.uid)) {
                return@respond FriendOutcome.Failure(FriendError.ALREADY_FRIENDS)
            }
            if (!target.acceptsRequests) {
                return@respond FriendOutcome.Failure(FriendError.REQUESTS_DISABLED)
            }

            val existing = pending(uid, target.uid)
            if (existing != null) {
                // Idempotente: o mesmo pedido volta, e nenhum segundo é criado.
                return@respond FriendOutcome.Success(
                    FriendRequestSent.AlreadyPending(toDomain(existing, uid))
                )
            }

            val inverse = pending(target.uid, uid)
            if (inverse != null) {
                // Cruzamento: os dois já pediram, então a amizade nasce agora.
                inverse.status = "ACCEPTED"
                val since = clock++
                friendships[pair(uid, target.uid)] = since
                return@respond FriendOutcome.Success(
                    FriendRequestSent.BecameFriends(
                        Friend(target.socialId, target.displayName, since)
                    )
                )
            }

            val created = StoredRequest(
                requestId = "req-${nextRequestId++}",
                requesterUid = uid,
                recipientUid = target.uid,
                status = "PENDING",
                createdAt = clock++
            )
            requests += created
            FriendOutcome.Success(FriendRequestSent.Created(toDomain(created, uid)))
        }
    }

    override suspend fun acceptRequest(requestId: String): FriendOutcome<Friend> {
        acceptCalls += 1
        return respond { uid ->
            val request = requests.firstOrNull { it.requestId == requestId }
                ?: return@respond FriendOutcome.Failure(FriendError.REQUEST_NOT_FOUND)
            // Participante errado nem descobre que o pedido existe.
            if (uid != request.requesterUid && uid != request.recipientUid) {
                return@respond FriendOutcome.Failure(FriendError.REQUEST_NOT_FOUND)
            }
            if (uid != request.recipientUid) {
                return@respond FriendOutcome.Failure(FriendError.NOT_ALLOWED)
            }
            val other = profilesById.values.first { it.uid == request.requesterUid }
            if (request.status == "ACCEPTED" && isFriendship(uid, other.uid)) {
                // Aceitar duas vezes é sucesso: o estado desejado já existe.
                return@respond FriendOutcome.Success(
                    Friend(other.socialId, other.displayName, friendships.getValue(pair(uid, other.uid)))
                )
            }
            if (request.status != "PENDING") {
                return@respond FriendOutcome.Failure(FriendError.REQUEST_NOT_PENDING)
            }

            request.status = "ACCEPTED"
            val since = clock++
            friendships[pair(uid, other.uid)] = since
            FriendOutcome.Success(Friend(other.socialId, other.displayName, since))
        }
    }

    override suspend fun rejectRequest(requestId: String): FriendOutcome<Unit> {
        rejectCalls += 1
        return resolve(requestId, "REJECTED") { it.recipientUid }
    }

    override suspend fun cancelRequest(requestId: String): FriendOutcome<Unit> {
        cancelCalls += 1
        return resolve(requestId, "CANCELLED") { it.requesterUid }
    }

    private suspend fun resolve(
        requestId: String,
        status: String,
        allowed: (StoredRequest) -> String
    ): FriendOutcome<Unit> = respond { uid ->
        val request = requests.firstOrNull { it.requestId == requestId }
            ?: return@respond FriendOutcome.Failure(FriendError.REQUEST_NOT_FOUND)
        if (uid != request.requesterUid && uid != request.recipientUid) {
            return@respond FriendOutcome.Failure(FriendError.REQUEST_NOT_FOUND)
        }
        if (uid != allowed(request)) {
            return@respond FriendOutcome.Failure(FriendError.NOT_ALLOWED)
        }
        when (request.status) {
            "PENDING" -> {
                request.status = status
                FriendOutcome.Success(Unit)
            }
            // Repetir a mesma resolução é idempotente.
            status -> FriendOutcome.Success(Unit)
            else -> FriendOutcome.Failure(FriendError.REQUEST_NOT_PENDING)
        }
    }

    override suspend fun removeFriend(socialId: String): FriendOutcome<Unit> {
        removeCalls += 1
        return respond { uid ->
            val target = profilesById[socialId]
                ?: return@respond FriendOutcome.Failure(FriendError.FRIENDSHIP_NOT_FOUND)
            if (friendships.remove(pair(uid, target.uid)) == null) {
                FriendOutcome.Failure(FriendError.FRIENDSHIP_NOT_FOUND)
            } else {
                FriendOutcome.Success(Unit)
            }
        }
    }

    override suspend fun friends(cursor: String?): FriendOutcome<FriendPage<Friend>> = respond { uid ->
        val items = friendships
            .filter { (key, _) -> key.first == uid || key.second == uid }
            .mapNotNull { (key, since) ->
                val otherUid = if (key.first == uid) key.second else key.first
                profilesById.values.firstOrNull { it.uid == otherUid && it.active }
                    ?.let { Friend(it.socialId, it.displayName, since) }
            }
            .sortedBy { it.displayName }
        FriendOutcome.Success(FriendPage(items, items.size))
    }

    override suspend fun incomingRequests(cursor: String?): FriendOutcome<FriendPage<FriendRequest>> =
        requestPage(FriendRequestDirection.INCOMING)

    override suspend fun outgoingRequests(cursor: String?): FriendOutcome<FriendPage<FriendRequest>> =
        requestPage(FriendRequestDirection.OUTGOING)

    private suspend fun requestPage(
        direction: FriendRequestDirection
    ): FriendOutcome<FriendPage<FriendRequest>> = respond { uid ->
        val items = requests
            .filter { it.status == "PENDING" }
            .filter {
                if (direction == FriendRequestDirection.INCOMING) {
                    it.recipientUid == uid
                } else {
                    it.requesterUid == uid
                }
            }
            .sortedByDescending { it.createdAt }
            .map { toDomain(it, uid) }
        FriendOutcome.Success(FriendPage(items, items.size))
    }

    // ------------------------------------------------------------------------------ apoio

    private fun relationshipOf(uid: String, otherUid: String): FriendRelationship = when {
        isFriendship(uid, otherUid) -> FriendRelationship.FRIENDS
        pending(uid, otherUid) != null -> FriendRelationship.OUTGOING_PENDING
        pending(otherUid, uid) != null -> FriendRelationship.INCOMING_PENDING
        else -> FriendRelationship.NONE
    }

    private fun pending(requesterUid: String, recipientUid: String): StoredRequest? =
        requests.firstOrNull {
            it.status == "PENDING" &&
                it.requesterUid == requesterUid &&
                it.recipientUid == recipientUid
        }

    private fun toDomain(request: StoredRequest, viewerUid: String): FriendRequest {
        val otherUid =
            if (request.requesterUid == viewerUid) request.recipientUid else request.requesterUid
        val other = profilesById.values.first { it.uid == otherUid }
        return FriendRequest(
            requestId = request.requestId,
            profile = SocialProfilePreview(other.socialId, other.displayName),
            direction = if (request.recipientUid == viewerUid) {
                FriendRequestDirection.INCOMING
            } else {
                FriendRequestDirection.OUTGOING
            },
            createdAt = request.createdAt
        )
    }

    /** O par canônico, como no banco: uma forma só de escrever a mesma relação. */
    private fun pair(uidA: String, uidB: String): Pair<String, String> =
        if (uidA < uidB) uidA to uidB else uidB to uidA

    private suspend fun <T> respond(block: (String) -> FriendOutcome<T>): FriendOutcome<T> {
        gate?.await()
        if (!isConfigured) return FriendOutcome.Failure(FriendError.NOT_CONFIGURED)
        failWith?.let { return FriendOutcome.Failure(it) }
        val uid = currentUid ?: return FriendOutcome.Failure(FriendError.AUTH_REQUIRED)
        return block(uid)
    }
}
