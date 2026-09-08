package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.FriendActivityItem
import com.example.domain.social.FriendRankingLeaderboard
import com.example.domain.social.SocialActivityError
import com.example.domain.social.SocialActivityGateway
import com.example.domain.social.SocialActivityOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Implementação HTTP do gateway de atividade e ranking social (T17.4).
 *
 * Utiliza o SparkBackendClient compartilhado (Bearer token).
 * Não grava dados em Room nem dispara mutações na Outbox.
 */
class SparkSocialActivityGateway(
    private val client: SparkBackendClient?
) : SocialActivityGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun getRecentFriendActivity(): SocialActivityOutcome<List<FriendActivityItem>> =
        get(SocialContract.ACTIVITY_PATH) { body ->
            val response = json.decodeFromString<SocialActivityResponseDto>(body)
            response.items.mapNotNull { it.toDomain() }
        }

    override suspend fun getFriendRankingLast7Days(): SocialActivityOutcome<FriendRankingLeaderboard> =
        get(SocialContract.RANKINGS_LAST_7_DAYS_PATH) { body ->
            val response = json.decodeFromString<FriendRankingResponseDto>(body)
            response.toDomain()
        }

    private suspend fun <T> get(
        path: String,
        parse: (String) -> T?
    ): SocialActivityOutcome<T> {
        val backend = client ?: return SocialActivityOutcome.Failure(SocialActivityError.NOT_CONFIGURED)
        if (!backend.isConfigured) {
            return SocialActivityOutcome.Failure(SocialActivityError.NOT_CONFIGURED)
        }
        return interpret(backend.getJson(path), parse)
    }

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): SocialActivityOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured ->
            SocialActivityOutcome.Failure(SocialActivityError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut ->
            SocialActivityOutcome.Failure(SocialActivityError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure ->
            SocialActivityOutcome.Failure(SocialActivityError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                null
            }
            if (parsed == null) {
                SocialActivityOutcome.Failure(SocialActivityError.REJECTED)
            } else {
                SocialActivityOutcome.Success(parsed)
            }
        } else {
            SocialActivityOutcome.Failure(errorOf(outcome))
        }
    }

    private fun errorOf(outcome: SparkHttpOutcome.Response): SocialActivityError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialContract.ErrorCodes.UNAUTHENTICATED -> SocialActivityError.AUTH_REQUIRED
            SocialContract.ErrorCodes.AUTH_UNAVAILABLE -> SocialActivityError.UNAVAILABLE
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED -> SocialActivityError.NOT_ENABLED
            SocialContract.ErrorCodes.RANKING_NOT_ENABLED -> SocialActivityError.RANKING_NOT_ENABLED
            SocialContract.ErrorCodes.SOCIAL_UNAVAILABLE -> SocialActivityError.UNAVAILABLE
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> SocialActivityError.AUTH_REQUIRED
                outcome.code == HTTP_FORBIDDEN && code == SocialContract.ErrorCodes.RANKING_NOT_ENABLED ->
                    SocialActivityError.RANKING_NOT_ENABLED
                outcome.code >= HTTP_SERVER_ERROR -> SocialActivityError.UNAVAILABLE
                else -> SocialActivityError.REJECTED
            }
        }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_SERVER_ERROR = 500
    }
}
