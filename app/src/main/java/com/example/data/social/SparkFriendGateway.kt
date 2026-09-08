package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.Friend
import com.example.domain.social.FriendError
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendLookup
import com.example.domain.social.FriendOutcome
import com.example.domain.social.FriendPage
import com.example.domain.social.FriendRequest
import com.example.domain.social.FriendRequestSent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A fronteira HTTP do grafo social (T17.1).
 *
 * ```text
 * FriendsViewModel → aqui → /v1/social/friends... (Bearer <Firebase ID Token>) → Spark Backend
 * ```
 *
 * Reusa o `SparkBackendClient` da T16.1: um cliente, um interceptor, um lugar montando
 * `Authorization: Bearer`. Nenhum caminho novo de autenticação nasceu aqui, e nenhum verbo HTTP
 * novo foi preciso — remover amigo é `POST` com o alvo no corpo, e não `DELETE` com o `socialId`
 * na URL, para que o identificador não passe por log de proxy.
 *
 * ## Sem conta, sem requisição
 *
 * Sem sessão do Firebase o interceptor não deixa a requisição sair, e o resultado é
 * [FriendError.AUTH_REQUIRED] com **zero** rede.
 *
 * ## Sem retry, sem fila, sem Outbox
 *
 * Uma ação explícita do usuário produz no máximo uma requisição. Offline, a ação **não acontece**:
 * ela não fica pendente e não é reenviada. "Recusei um pedido no avião e ele foi aceito sozinho
 * três dias depois" seria pior do que "não deu, tente com internet".
 *
 * ## Nada é registrado em log
 *
 * Este pacote não escreve log nenhum, e a ausência é testada. `friendCode`, `socialId` e nome
 * social não vão para o Logcat — nem em debug, onde um relatório de bug os levaria junto.
 */
class SparkFriendGateway(
    private val client: SparkBackendClient?
) : FriendGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun lookup(friendCode: String): FriendOutcome<FriendLookup> =
        post(FriendshipContract.LOOKUP_PATH, json.encodeToString(FriendLookupRequestDto(friendCode))) { body ->
            val dto = json.decodeFromString<FriendLookupResponseDto>(body)
            when (dto.result) {
                "SELF" -> FriendLookup.Self
                "NOT_FOUND" -> FriendLookup.NotFound
                "FOUND" -> {
                    val preview = dto.profile?.toDomain()
                    val relationship = parseRelationship(dto.relationship)
                    // Um `FOUND` sem perfil ou com relação que este APK não conhece não vira um
                    // resultado pela metade: ele é defeito de contrato, e a tela diz isso.
                    if (preview == null || relationship == null) {
                        null
                    } else {
                        FriendLookup.Found(
                            profile = preview,
                            relationship = relationship,
                            canSendFriendRequest = dto.canSendFriendRequest
                        )
                    }
                }
                else -> null
            }
        }

    override suspend fun sendRequest(socialId: String): FriendOutcome<FriendRequestSent> =
        post(
            FriendshipContract.FRIEND_REQUESTS_PATH,
            json.encodeToString(SocialIdTargetDto(socialId))
        ) { body ->
            val dto = json.decodeFromString<SendFriendRequestResponseDto>(body)
            when (dto.result) {
                "REQUEST_CREATED" -> dto.request?.toDomain()?.let { FriendRequestSent.Created(it) }
                "REQUEST_ALREADY_PENDING" ->
                    dto.request?.toDomain()?.let { FriendRequestSent.AlreadyPending(it) }
                "FRIENDSHIP_CREATED" ->
                    dto.friend?.toDomain()?.let { FriendRequestSent.BecameFriends(it) }
                else -> null
            }
        }

    override suspend fun acceptRequest(requestId: String): FriendOutcome<Friend> =
        post(FriendshipContract.acceptPath(requestId), EMPTY_BODY) { body ->
            json.decodeFromString<AcceptFriendRequestResponseDto>(body).friend.toDomain()
        }

    override suspend fun rejectRequest(requestId: String): FriendOutcome<Unit> =
        post(FriendshipContract.rejectPath(requestId), EMPTY_BODY) { body ->
            json.decodeFromString<SimpleResultDto>(body).result.takeIf { it.isNotBlank() }?.let { }
        }

    override suspend fun cancelRequest(requestId: String): FriendOutcome<Unit> =
        post(FriendshipContract.cancelPath(requestId), EMPTY_BODY) { body ->
            json.decodeFromString<SimpleResultDto>(body).result.takeIf { it.isNotBlank() }?.let { }
        }

    override suspend fun removeFriend(socialId: String): FriendOutcome<Unit> =
        post(
            FriendshipContract.REMOVE_FRIEND_PATH,
            json.encodeToString(SocialIdTargetDto(socialId))
        ) { body ->
            json.decodeFromString<SimpleResultDto>(body).result.takeIf { it.isNotBlank() }?.let { }
        }

    override suspend fun friends(cursor: String?): FriendOutcome<FriendPage<Friend>> =
        get(pathWithCursor(FriendshipContract.FRIENDS_PATH, cursor)) { body ->
            val dto = json.decodeFromString<FriendListResponseDto>(body)
            val friends = dto.friends.map { it.toDomain() }
            // Um item ilegível recusa a página inteira: uma lista de amigos com um buraco no meio
            // é pior do que uma tela que diz que não conseguiu ler a resposta.
            if (friends.any { it == null }) {
                null
            } else {
                FriendPage(friends.filterNotNull(), dto.total, dto.nextCursor)
            }
        }

    override suspend fun incomingRequests(cursor: String?): FriendOutcome<FriendPage<FriendRequest>> =
        requests(FriendshipContract.INCOMING_PATH, cursor)

    override suspend fun outgoingRequests(cursor: String?): FriendOutcome<FriendPage<FriendRequest>> =
        requests(FriendshipContract.OUTGOING_PATH, cursor)

    private suspend fun requests(
        path: String,
        cursor: String?
    ): FriendOutcome<FriendPage<FriendRequest>> = get(pathWithCursor(path, cursor)) { body ->
        val dto = json.decodeFromString<FriendRequestListResponseDto>(body)
        val requests = dto.requests.map { it.toDomain() }
        if (requests.any { it == null }) {
            null
        } else {
            FriendPage(requests.filterNotNull(), dto.total, dto.nextCursor)
        }
    }

    private fun pathWithCursor(path: String, cursor: String?): String =
        if (cursor.isNullOrBlank()) path else "$path?cursor=${encode(cursor)}"

    /**
     * O cursor é opaco e vem do servidor, mas vai na URL — então ele é escapado.
     *
     * `java.net.URLEncoder` produz `+` para espaço, que numa *query string* significa espaço e é
     * decodificado de volta corretamente. O cursor é base64url e não contém espaço; o escape
     * existe para que isso continue verdade se o formato do servidor mudar.
     */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private suspend fun <T> post(
        path: String,
        body: String,
        parse: (String) -> T?
    ): FriendOutcome<T> {
        val backend = client ?: return FriendOutcome.Failure(FriendError.NOT_CONFIGURED)
        if (!backend.isConfigured) return FriendOutcome.Failure(FriendError.NOT_CONFIGURED)
        return interpret(backend.postJson(path, body), parse)
    }

    private suspend fun <T> get(path: String, parse: (String) -> T?): FriendOutcome<T> {
        val backend = client ?: return FriendOutcome.Failure(FriendError.NOT_CONFIGURED)
        if (!backend.isConfigured) return FriendOutcome.Failure(FriendError.NOT_CONFIGURED)
        return interpret(backend.getJson(path), parse)
    }

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): FriendOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured -> FriendOutcome.Failure(FriendError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut -> FriendOutcome.Failure(FriendError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure -> FriendOutcome.Failure(FriendError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                // Um corpo que este APK não sabe ler não vira meia amizade.
                null
            }
            @Suppress("UNCHECKED_CAST")
            if (parsed == null) {
                FriendOutcome.Failure(FriendError.REJECTED)
            } else {
                FriendOutcome.Success(parsed)
            }
        } else {
            FriendOutcome.Failure(errorOf(outcome))
        }
    }

    /**
     * O `code` do envelope de erro decide — e o status HTTP é só o desempate.
     *
     * O envelope da T16.0 (`{ error: { code, message, requestId } }`) existe para isto: um `409`
     * de "vocês já são amigos" e um `409` de "este pedido já foi resolvido" levam a telas
     * diferentes, e só o `code` os distingue.
     */
    private fun errorOf(outcome: SparkHttpOutcome.Response): FriendError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialContract.ErrorCodes.UNAUTHENTICATED -> FriendError.AUTH_REQUIRED
            SocialContract.ErrorCodes.AUTH_UNAVAILABLE -> FriendError.UNAVAILABLE
            SocialContract.ErrorCodes.API_RATE_LIMITED -> FriendError.RATE_LIMITED
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED -> FriendError.SOCIAL_NOT_ENABLED
            SocialContract.ErrorCodes.SOCIAL_UNAVAILABLE -> FriendError.UNAVAILABLE
            FriendshipContract.ErrorCodes.SOCIAL_PROFILE_DISABLED -> FriendError.SOCIAL_DISABLED
            FriendshipContract.ErrorCodes.SOCIAL_PROFILE_NOT_FOUND -> FriendError.PROFILE_NOT_FOUND
            FriendshipContract.ErrorCodes.SELF_FRIEND_REQUEST -> FriendError.SELF_REQUEST
            FriendshipContract.ErrorCodes.FRIEND_REQUESTS_DISABLED -> FriendError.REQUESTS_DISABLED
            FriendshipContract.ErrorCodes.ALREADY_FRIENDS -> FriendError.ALREADY_FRIENDS
            FriendshipContract.ErrorCodes.FRIEND_REQUEST_NOT_FOUND -> FriendError.REQUEST_NOT_FOUND
            FriendshipContract.ErrorCodes.FRIEND_REQUEST_NOT_PENDING ->
                FriendError.REQUEST_NOT_PENDING
            FriendshipContract.ErrorCodes.NOT_REQUEST_RECIPIENT -> FriendError.NOT_ALLOWED
            FriendshipContract.ErrorCodes.NOT_REQUEST_SENDER -> FriendError.NOT_ALLOWED
            FriendshipContract.ErrorCodes.FRIENDSHIP_NOT_FOUND -> FriendError.FRIENDSHIP_NOT_FOUND
            FriendshipContract.ErrorCodes.SOCIAL_RATE_LIMITED -> FriendError.RATE_LIMITED
            FriendshipContract.ErrorCodes.INVALID_FRIEND_REQUEST -> FriendError.REJECTED
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> FriendError.AUTH_REQUIRED
                outcome.code == HTTP_TOO_MANY_REQUESTS -> FriendError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> FriendError.UNAVAILABLE
                else -> FriendError.REJECTED
            }
        }
    }

    private companion object {
        /** `{}` em vez de corpo vazio: aceitar/recusar/cancelar não leem o corpo, e um JSON
         *  válido evita depender de como cada parser trata `Content-Type: application/json` sem
         *  conteúdo. */
        const val EMPTY_BODY = "{}"
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
