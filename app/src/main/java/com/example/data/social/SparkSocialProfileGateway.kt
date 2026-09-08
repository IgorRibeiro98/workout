package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ProgressSharing
import com.example.domain.social.SocialProfileError
import com.example.domain.social.SocialProfileGateway
import com.example.domain.social.SocialProfileOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A fronteira HTTP do perfil social enriquecido (T17.2).
 *
 * ```text
 * SocialProfileViewModel → aqui → /v1/social/... (Bearer <Firebase ID Token>) → Spark Backend
 * ```
 *
 * Reusa o `SparkBackendClient` da T16.1, como a T17.0 e a T17.1: um cliente, um interceptor, um
 * lugar montando `Authorization: Bearer`. Nenhum caminho novo de autenticação nasceu aqui.
 *
 * ## Sem conta, sem requisição
 *
 * Sem sessão do Firebase o interceptor não deixa a requisição sair, e o resultado é
 * [SocialProfileError.AUTH_REQUIRED] com **zero** rede.
 *
 * ## Sem retry, sem fila, sem Outbox
 *
 * Alterar o compartilhamento offline **não acontece**: a ação não fica pendente e não é reenviada.
 * "Desliguei o compartilhamento no avião e ele continuou ligado por três dias" seria o pior
 * defeito possível numa tela de privacidade — e "não deu, tente com internet" é honesto.
 *
 * ## Nada é registrado em log
 *
 * Este pacote não escreve log nenhum, e a ausência é testada. Nome social, `socialId`, nível e
 * contagem de treinos não vão para o Logcat — nem em debug, onde um relatório de bug os levaria
 * junto.
 */
class SparkSocialProfileGateway(
    private val client: SparkBackendClient?
) : SocialProfileGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        // O que o app não quer alterar **não vai** no corpo — é isso que dá semântica de `PATCH`
        // ao nulo. Enviar `null` faria o servidor recusar (o campo precisa ser booleano) ou, pior
        // num contrato mais frouxo, faria "não mexa" virar "desligue".
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun friendProfile(
        socialId: String
    ): SocialProfileOutcome<FriendSocialProfile> =
        get(SocialProfileContract.friendProfilePath(socialId)) { body ->
            json.decodeFromString<FriendSocialProfileResponseDto>(body).profile.toDomain()
        }

    override suspend fun profilePreview(): SocialProfileOutcome<FriendSocialProfile> =
        get(SocialProfileContract.PROFILE_PREVIEW_PATH) { body ->
            json.decodeFromString<FriendSocialProfileResponseDto>(body).profile.toDomain()
        }

    override suspend fun progressSharing(): SocialProfileOutcome<ProgressSharing> =
        get(SocialProfileContract.PROGRESS_SHARING_PATH) { body ->
            json.decodeFromString<ProgressSharingResponseDto>(body).toDomain()
        }

    override suspend fun updateProgressSharing(
        shareLevel: Boolean?,
        shareConsistencyStreak: Boolean?,
        shareWeeklyWorkoutCount: Boolean?,
        shareHighlightedAchievements: Boolean?,
        weekTimeZone: String?
    ): SocialProfileOutcome<ProgressSharing> {
        val body = json.encodeToString(
            UpdateProgressSharingRequestDto(
                shareLevel = shareLevel,
                shareConsistencyStreak = shareConsistencyStreak,
                shareWeeklyWorkoutCount = shareWeeklyWorkoutCount,
                shareHighlightedAchievements = shareHighlightedAchievements,
                weekTimeZone = weekTimeZone
            )
        )
        return patch(SocialProfileContract.PROGRESS_SHARING_PATH, body) { response ->
            json.decodeFromString<ProgressSharingResponseDto>(response).toDomain()
        }
    }

    private suspend fun <T> get(
        path: String,
        parse: (String) -> T?
    ): SocialProfileOutcome<T> {
        val backend = client ?: return SocialProfileOutcome.Failure(SocialProfileError.NOT_CONFIGURED)
        if (!backend.isConfigured) {
            return SocialProfileOutcome.Failure(SocialProfileError.NOT_CONFIGURED)
        }
        return interpret(backend.getJson(path), parse)
    }

    private suspend fun <T> patch(
        path: String,
        body: String,
        parse: (String) -> T?
    ): SocialProfileOutcome<T> {
        val backend = client ?: return SocialProfileOutcome.Failure(SocialProfileError.NOT_CONFIGURED)
        if (!backend.isConfigured) {
            return SocialProfileOutcome.Failure(SocialProfileError.NOT_CONFIGURED)
        }
        return interpret(backend.patchJson(path, body), parse)
    }

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): SocialProfileOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured ->
            SocialProfileOutcome.Failure(SocialProfileError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut ->
            SocialProfileOutcome.Failure(SocialProfileError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure ->
            SocialProfileOutcome.Failure(SocialProfileError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                // Um corpo que este APK não sabe ler não vira meio perfil.
                null
            }
            if (parsed == null) {
                SocialProfileOutcome.Failure(SocialProfileError.REJECTED)
            } else {
                SocialProfileOutcome.Success(parsed)
            }
        } else {
            SocialProfileOutcome.Failure(errorOf(outcome))
        }
    }

    /**
     * O `code` do envelope de erro decide — e o status HTTP é só o desempate.
     *
     * `FRIEND_PROFILE_NOT_FOUND` vira um estado só ([SocialProfileError.PROFILE_UNAVAILABLE]),
     * porque o servidor responde a mesma coisa para quatro situações diferentes de propósito. O app
     * não tenta adivinhar qual delas foi: adivinhar seria reconstruir, na tela, a informação que o
     * servidor recusou dar.
     */
    private fun errorOf(outcome: SparkHttpOutcome.Response): SocialProfileError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialContract.ErrorCodes.UNAUTHENTICATED -> SocialProfileError.AUTH_REQUIRED
            SocialContract.ErrorCodes.AUTH_UNAVAILABLE -> SocialProfileError.UNAVAILABLE
            SocialContract.ErrorCodes.API_RATE_LIMITED -> SocialProfileError.RATE_LIMITED
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED -> SocialProfileError.SOCIAL_NOT_ENABLED
            SocialContract.ErrorCodes.SOCIAL_UNAVAILABLE -> SocialProfileError.UNAVAILABLE
            FriendshipContract.ErrorCodes.SOCIAL_PROFILE_DISABLED -> SocialProfileError.SOCIAL_DISABLED
            FriendshipContract.ErrorCodes.SOCIAL_RATE_LIMITED -> SocialProfileError.RATE_LIMITED
            SocialProfileContract.ErrorCodes.FRIEND_PROFILE_NOT_FOUND ->
                SocialProfileError.PROFILE_UNAVAILABLE
            SocialProfileContract.ErrorCodes.INVALID_PROGRESS_SETTINGS ->
                SocialProfileError.INVALID_SETTINGS
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> SocialProfileError.AUTH_REQUIRED
                outcome.code == HTTP_NOT_FOUND -> SocialProfileError.PROFILE_UNAVAILABLE
                outcome.code == HTTP_TOO_MANY_REQUESTS -> SocialProfileError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> SocialProfileError.UNAVAILABLE
                else -> SocialProfileError.REJECTED
            }
        }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
