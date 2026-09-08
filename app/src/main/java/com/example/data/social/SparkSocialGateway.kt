package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.SocialDiscoverability
import com.example.domain.social.SocialError
import com.example.domain.social.SocialGateway
import com.example.domain.social.SocialOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A fronteira HTTP do domínio social (T17.0).
 *
 * ```text
 * SocialViewModel → aqui → /v1/social/me... (Bearer <Firebase ID Token>) → Spark Backend
 * ```
 *
 * Reusa o `SparkBackendClient` da T16.1: um cliente, um interceptor, um lugar montando
 * `Authorization: Bearer`. Nenhum caminho novo de autenticação foi criado, e o token continua
 * sendo pedido ao Firebase na hora e usado na hora — nunca guardado, nunca registrado.
 *
 * ## Sem conta, sem requisição
 *
 * Sem sessão do Firebase o interceptor não deixa a requisição sair, e o resultado é
 * [SocialError.AUTH_REQUIRED] com **zero** rede. Isso não afeta treino, execução, histórico,
 * templates, gamificação, backup nem sincronização.
 *
 * ## Sem retry, sem fila, sem Outbox
 *
 * Uma ação explícita do usuário produz no máximo uma requisição. Offline, a ação **não acontece**:
 * ela não vai para a Outbox, não fica pendente e não é reenviada depois. Isso é deliberado —
 * "editei meu nome no avião e ele mudou sozinho três dias depois" seria pior do que "não deu,
 * tente com internet". O treino continua local-first; o social, não.
 *
 * ## Nada é registrado em log
 *
 * Este pacote não escreve log nenhum, e a ausência é testada. Nome social, `friendCode` e
 * `socialId` não vão para o Logcat — nem em debug, onde qualquer app com permissão de leitura de
 * log antigo ou um relatório de bug os levaria junto.
 */
class SparkSocialGateway(
    private val client: SparkBackendClient?
) : SocialGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        // `null` significa "não mexer neste campo" na privacidade parcial: ele é omitido do corpo,
        // e não enviado como `null` — que o servidor recusaria por tipo, corretamente.
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun profile(): SocialOutcome {
        val backend = client ?: return SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
        if (!backend.isConfigured) return SocialOutcome.Failure(SocialError.NOT_CONFIGURED)

        return when (val outcome = backend.getJson(SocialContract.ME_PATH)) {
            SparkHttpOutcome.NotConfigured -> SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
            SparkHttpOutcome.SignedOut -> SocialOutcome.Failure(SocialError.AUTH_REQUIRED)
            SparkHttpOutcome.NetworkFailure -> SocialOutcome.Failure(SocialError.NETWORK)
            is SparkHttpOutcome.Response -> interpretMe(outcome)
        }
    }

    override suspend fun activate(displayName: String): SocialOutcome = post(
        path = SocialContract.ACTIVATE_PATH,
        body = json.encodeToString(ActivateSocialRequestDto(displayName = displayName))
    )

    override suspend fun updateDisplayName(displayName: String): SocialOutcome = patch(
        path = SocialContract.PROFILE_PATH,
        body = json.encodeToString(UpdateSocialProfileRequestDto(displayName = displayName))
    )

    override suspend fun updatePrivacy(
        discoverability: SocialDiscoverability?,
        friendRequestsEnabled: Boolean?,
        activitySharingEnabled: Boolean?,
        activityTimeZoneId: String?,
        friendRankingParticipationEnabled: Boolean?
    ): SocialOutcome = patch(
        path = SocialContract.PRIVACY_PATH,
        body = json.encodeToString(
            UpdateSocialPrivacyRequestDto(
                discoverability = discoverability?.name,
                friendRequestsEnabled = friendRequestsEnabled,
                activitySharingEnabled = activitySharingEnabled,
                activityTimeZoneId = activityTimeZoneId,
                friendRankingParticipationEnabled = friendRankingParticipationEnabled
            )
        )
    )

    override suspend fun disable(): SocialOutcome = post(SocialContract.DISABLE_PATH, EMPTY_BODY)

    override suspend fun enable(): SocialOutcome = post(SocialContract.ENABLE_PATH, EMPTY_BODY)

    private suspend fun post(path: String, body: String): SocialOutcome {
        val backend = client ?: return SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
        if (!backend.isConfigured) return SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
        return interpretProfile(backend.postJson(path, body))
    }

    private suspend fun patch(path: String, body: String): SocialOutcome {
        val backend = client ?: return SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
        if (!backend.isConfigured) return SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
        return interpretProfile(backend.patchJson(path, body))
    }

    private fun interpretProfile(outcome: SparkHttpOutcome): SocialOutcome = when (outcome) {
        SparkHttpOutcome.NotConfigured -> SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut -> SocialOutcome.Failure(SocialError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure -> SocialOutcome.Failure(SocialError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            parseProfile(outcome.body)
        } else {
            SocialOutcome.Failure(errorOf(outcome))
        }
    }

    /**
     * `GET /me` tem um caso a mais: `{ "enabled": false }`.
     *
     * Ele chega como `200`, e não como `404`, porque "ainda não ativei" é um estado normal do
     * produto. Tratá-lo como erro faria a tela mostrar falha para o caminho mais comum de todos.
     */
    private fun interpretMe(outcome: SparkHttpOutcome.Response): SocialOutcome {
        if (outcome.code !in SUCCESS_RANGE) {
            return SocialOutcome.Failure(errorOf(outcome))
        }
        val response = try {
            json.decodeFromString<SocialMeResponseDto>(outcome.body)
        } catch (e: SerializationException) {
            return SocialOutcome.Failure(SocialError.REJECTED)
        }

        if (!response.enabled) return SocialOutcome.NotEnabled
        val profile = response.profile?.toDomain()
            ?: return SocialOutcome.Failure(SocialError.REJECTED)
        return SocialOutcome.Success(profile)
    }

    private fun parseProfile(body: String): SocialOutcome {
        val response = try {
            json.decodeFromString<SocialProfileResponseDto>(body)
        } catch (e: SerializationException) {
            // Um corpo que este APK não sabe ler não vira um perfil pela metade.
            return SocialOutcome.Failure(SocialError.REJECTED)
        }
        return response.profile.toDomain()?.let { SocialOutcome.Success(it) }
            ?: SocialOutcome.Failure(SocialError.REJECTED)
    }

    /**
     * O `code` do envelope de erro decide — e o status HTTP é só o desempate.
     *
     * O envelope da T16.0 (`{ error: { code, message, requestId } }`) existe exatamente para isso:
     * um `503` de manutenção e um `503` de código esgotado levam à mesma tela, mas um `409` de
     * "já estava ativo" não é falha nenhuma, e só o `code` distingue.
     */
    private fun errorOf(outcome: SparkHttpOutcome.Response): SocialError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialContract.ErrorCodes.UNAUTHENTICATED -> SocialError.AUTH_REQUIRED
            SocialContract.ErrorCodes.AUTH_UNAVAILABLE -> SocialError.UNAVAILABLE
            SocialContract.ErrorCodes.API_RATE_LIMITED -> SocialError.RATE_LIMITED
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED -> SocialError.NOT_ENABLED
            SocialContract.ErrorCodes.SOCIAL_ALREADY_ENABLED -> SocialError.ALREADY_ENABLED
            SocialContract.ErrorCodes.SOCIAL_ALREADY_DISABLED -> SocialError.ALREADY_DISABLED
            SocialContract.ErrorCodes.INVALID_DISPLAY_NAME -> SocialError.INVALID_DISPLAY_NAME
            SocialContract.ErrorCodes.SOCIAL_UNAVAILABLE -> SocialError.UNAVAILABLE
            SocialContract.ErrorCodes.INVALID_SOCIAL_REQUEST -> SocialError.REJECTED
            SocialContract.ErrorCodes.RANKING_NOT_ENABLED -> SocialError.RANKING_NOT_ENABLED
            SocialContract.ErrorCodes.INVALID_ACTIVITY_TIMEZONE -> SocialError.INVALID_ACTIVITY_TIMEZONE
            SocialContract.ErrorCodes.ACTIVITY_NOT_AVAILABLE -> SocialError.ACTIVITY_NOT_AVAILABLE
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> SocialError.AUTH_REQUIRED
                outcome.code == HTTP_TOO_MANY_REQUESTS -> SocialError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> SocialError.UNAVAILABLE
                else -> SocialError.REJECTED
            }
        }
    }

    private companion object {
        /** `{}` em vez de corpo vazio: `disable`/`enable` não leem o corpo, e um JSON válido evita
         *  depender de como cada parser trata um `Content-Type: application/json` sem conteúdo. */
        const val EMPTY_BODY = "{}"
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

/** O envelope de erro do Spark Backend (T16.0), na parte que o social precisa ler. */
@kotlinx.serialization.Serializable
internal data class ErrorEnvelopeDto(val error: ErrorBodyDto)

@kotlinx.serialization.Serializable
internal data class ErrorBodyDto(val code: String = "", val message: String = "")
