package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.Challenge
import com.example.domain.social.ChallengeDetail
import com.example.domain.social.ChallengeError
import com.example.domain.social.ChallengeGateway
import com.example.domain.social.ChallengeInvitePage
import com.example.domain.social.ChallengeOutcome
import com.example.domain.social.ChallengePage
import com.example.domain.social.ChallengeType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A fronteira HTTP dos desafios (T17.3).
 *
 * ```text
 * ChallengeViewModel → aqui → /v1/social/challenges... (Bearer <Firebase ID Token>) → Backend
 * ```
 *
 * Reusa o `SparkBackendClient` da T16.1, como a T17.0, a T17.1 e a T17.2: um cliente, um
 * interceptor, um lugar montando `Authorization: Bearer`. Nenhum caminho novo de autenticação
 * nasceu aqui, e nenhum verbo HTTP novo foi preciso.
 *
 * ## Sem conta, sem requisição
 *
 * Sem sessão do Firebase o interceptor não deixa a requisição sair, e o resultado é
 * [ChallengeError.AUTH_REQUIRED] com **zero** rede.
 *
 * ## Sem retry, sem fila, sem Outbox
 *
 * Uma ação explícita produz no máximo uma requisição. Offline, criar, aceitar, recusar, sair e
 * cancelar **não acontecem**: não ficam pendentes e não são reenviados. "Aceitei um desafio no
 * avião e ele apareceu três dias depois, já começado" seria pior do que "não deu, tente com
 * internet".
 *
 * O `clientRequestId` da criação **não** é uma fila: ele existe para que um retry que o usuário
 * decida fazer devolva o desafio que já foi criado, em vez de criar um segundo.
 *
 * ## Nenhuma pontuação sai daqui
 *
 * Não há método, parâmetro ou DTO de escrita que carregue `score`, `progress`, `rank` ou `winner`.
 * O placar é sempre leitura, e ele é do servidor.
 *
 * ## Nada é registrado em log
 *
 * Este pacote não escreve log nenhum, e a ausência é testada. Nome de desafio, `socialId`, nome
 * social e pontuação não vão para o Logcat — nem em debug, onde um relatório de bug os levaria
 * junto.
 */
class SparkChallengeGateway(
    private val client: SparkBackendClient?
) : ChallengeGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun create(
        clientRequestId: String,
        name: String,
        type: ChallengeType,
        target: Int,
        startDate: String,
        endDate: String,
        timeZoneId: String,
        invitedSocialIds: List<String>
    ): ChallengeOutcome<Challenge> {
        val body = json.encodeToString(
            CreateChallengeRequestDto(
                clientRequestId = clientRequestId,
                name = name,
                type = type.name,
                target = target,
                startDate = startDate,
                endDate = endDate,
                timeZoneId = timeZoneId,
                // A lista já chega sem duplicata do ViewModel (é um `Set`), e o servidor
                // normaliza de novo: a garantia é dele, não da tela.
                invitedSocialIds = invitedSocialIds
            )
        )
        return post(ChallengeContract.CHALLENGES_PATH, body) { response ->
            json.decodeFromString<CreateChallengeResponseDto>(response).challenge.toDomain()
        }
    }

    override suspend fun list(cursor: String?): ChallengeOutcome<ChallengePage> =
        get(ChallengeContract.withCursor(ChallengeContract.CHALLENGES_PATH, cursor)) { body ->
            json.decodeFromString<ChallengeListResponseDto>(body).toDomain()
        }

    override suspend fun detail(challengeId: String): ChallengeOutcome<ChallengeDetail> =
        get(ChallengeContract.challengePath(challengeId)) { body ->
            json.decodeFromString<ChallengeDetailResponseDto>(body).toDomain()
        }

    override suspend fun invites(cursor: String?): ChallengeOutcome<ChallengeInvitePage> =
        get(ChallengeContract.withCursor(ChallengeContract.INVITATIONS_PATH, cursor)) { body ->
            json.decodeFromString<ChallengeInvitationListResponseDto>(body).toDomain()
        }

    override suspend fun accept(invitationId: String): ChallengeOutcome<Challenge> =
        post(ChallengeContract.acceptPath(invitationId), EMPTY_BODY) { body ->
            json.decodeFromString<AcceptChallengeResponseDto>(body).challenge.toDomain()
        }

    override suspend fun decline(invitationId: String): ChallengeOutcome<Unit> =
        post(ChallengeContract.declinePath(invitationId), EMPTY_BODY) { body ->
            // O desfecho exato (`DECLINED` ou `ALREADY_DECLINED`) não muda o que a tela faz: os
            // dois são sucesso, e o convite sai da lista. Ler o corpo mesmo assim é a validação de
            // que a resposta é a que este APK entende.
            json.decodeFromString<ChallengeResultDto>(body).let { }
        }

    override suspend fun leave(challengeId: String): ChallengeOutcome<Unit> =
        post(ChallengeContract.leavePath(challengeId), EMPTY_BODY) { body ->
            json.decodeFromString<ChallengeResultDto>(body).let { }
        }

    override suspend fun cancel(challengeId: String): ChallengeOutcome<Unit> =
        post(ChallengeContract.cancelPath(challengeId), EMPTY_BODY) { body ->
            json.decodeFromString<ChallengeResultDto>(body).let { }
        }

    // ------------------------------------------------------------------------------- transporte

    private suspend fun <T> get(
        path: String,
        parse: (String) -> T?
    ): ChallengeOutcome<T> {
        val backend = client ?: return ChallengeOutcome.Failure(ChallengeError.NOT_CONFIGURED)
        if (!backend.isConfigured) {
            return ChallengeOutcome.Failure(ChallengeError.NOT_CONFIGURED)
        }
        return interpret(backend.getJson(path), parse)
    }

    private suspend fun <T> post(
        path: String,
        body: String,
        parse: (String) -> T?
    ): ChallengeOutcome<T> {
        val backend = client ?: return ChallengeOutcome.Failure(ChallengeError.NOT_CONFIGURED)
        if (!backend.isConfigured) {
            return ChallengeOutcome.Failure(ChallengeError.NOT_CONFIGURED)
        }
        return interpret(backend.postJson(path, body), parse)
    }

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): ChallengeOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured ->
            ChallengeOutcome.Failure(ChallengeError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut ->
            ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure ->
            ChallengeOutcome.Failure(ChallengeError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                // Um corpo que este APK não sabe ler não vira meio desafio.
                null
            }
            // `Unit` é um objeto em Kotlin, então as rotas sem corpo útil (recusar, sair,
            // cancelar) chegam aqui como `parsed != null` — elas passam pelo mesmo caminho, sem
            // precisar de um ramo próprio.
            if (parsed == null) {
                ChallengeOutcome.Failure(ChallengeError.REJECTED)
            } else {
                ChallengeOutcome.Success(parsed)
            }
        } else {
            ChallengeOutcome.Failure(errorOf(outcome))
        }
    }

    /**
     * O `code` do envelope de erro decide — e o status HTTP é só o desempate.
     *
     * `CHALLENGE_NOT_FOUND` vira um estado só ([ChallengeError.NOT_FOUND]), porque o servidor
     * responde a mesma coisa para "não existe", "é de outra pessoa" e "só fui convidado", de
     * propósito. O app não tenta adivinhar qual: adivinhar seria reconstruir, na tela, a
     * informação que o servidor recusou dar.
     */
    private fun errorOf(outcome: SparkHttpOutcome.Response): ChallengeError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialContract.ErrorCodes.UNAUTHENTICATED -> ChallengeError.AUTH_REQUIRED
            SocialContract.ErrorCodes.AUTH_UNAVAILABLE -> ChallengeError.UNAVAILABLE
            SocialContract.ErrorCodes.API_RATE_LIMITED -> ChallengeError.RATE_LIMITED
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED -> ChallengeError.SOCIAL_NOT_ENABLED
            SocialContract.ErrorCodes.SOCIAL_UNAVAILABLE -> ChallengeError.UNAVAILABLE
            FriendshipContract.ErrorCodes.SOCIAL_PROFILE_DISABLED -> ChallengeError.SOCIAL_DISABLED
            ChallengeContract.ErrorCodes.CHALLENGE_RATE_LIMITED -> ChallengeError.RATE_LIMITED
            ChallengeContract.ErrorCodes.CHALLENGE_NOT_FOUND -> ChallengeError.NOT_FOUND
            ChallengeContract.ErrorCodes.CHALLENGE_INVITATION_NOT_FOUND ->
                ChallengeError.INVITATION_NOT_FOUND
            ChallengeContract.ErrorCodes.CHALLENGE_INVITATION_NOT_PENDING ->
                ChallengeError.INVITATION_NOT_PENDING
            ChallengeContract.ErrorCodes.CHALLENGE_ALREADY_STARTED -> ChallengeError.ALREADY_STARTED
            ChallengeContract.ErrorCodes.CHALLENGE_CANCELLED -> ChallengeError.CANCELLED
            ChallengeContract.ErrorCodes.NOT_CHALLENGE_CREATOR -> ChallengeError.NOT_CREATOR
            ChallengeContract.ErrorCodes.CANNOT_LEAVE_AS_CREATOR ->
                ChallengeError.CANNOT_LEAVE_AS_CREATOR
            ChallengeContract.ErrorCodes.CHALLENGE_PARTICIPANT_NOT_AVAILABLE ->
                ChallengeError.PARTICIPANT_NOT_AVAILABLE
            ChallengeContract.ErrorCodes.TOO_MANY_PARTICIPANTS ->
                ChallengeError.TOO_MANY_PARTICIPANTS
            ChallengeContract.ErrorCodes.TOO_MANY_OPEN_CHALLENGES ->
                ChallengeError.TOO_MANY_OPEN_CHALLENGES
            // Os cinco erros de forma viram um estado só: a tela de criação aponta o campo pelo
            // que ela mesma validou, e o servidor é a autoridade sobre o resto.
            ChallengeContract.ErrorCodes.INVALID_CHALLENGE_REQUEST,
            ChallengeContract.ErrorCodes.INVALID_CHALLENGE_TYPE,
            ChallengeContract.ErrorCodes.INVALID_CHALLENGE_TARGET,
            ChallengeContract.ErrorCodes.INVALID_CHALLENGE_PERIOD,
            ChallengeContract.ErrorCodes.INVALID_CHALLENGE_TIMEZONE ->
                ChallengeError.INVALID_CHALLENGE
            // Um `clientRequestId` reusado com conteúdo diferente. Não é retry, é defeito — e o
            // app trata como recusa em vez de tentar de novo com o mesmo id.
            ChallengeContract.ErrorCodes.CHALLENGE_IDEMPOTENCY_CONFLICT -> ChallengeError.REJECTED
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> ChallengeError.AUTH_REQUIRED
                outcome.code == HTTP_NOT_FOUND -> ChallengeError.NOT_FOUND
                outcome.code == HTTP_TOO_MANY_REQUESTS -> ChallengeError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> ChallengeError.UNAVAILABLE
                else -> ChallengeError.REJECTED
            }
        }
    }

    private companion object {
        /** As rotas de resposta a convite e de saída não têm corpo de entrada. */
        const val EMPTY_BODY = "{}"
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
