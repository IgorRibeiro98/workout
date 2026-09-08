package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.BlockError
import com.example.domain.social.BlockGateway
import com.example.domain.social.BlockOutcome
import com.example.domain.social.BlockedUser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A fronteira HTTP de bloqueio social com o Spark Backend (T17.6).
 *
 * Bloquear é server-authoritative e bilateral.
 * Sem Room, sem cache durável e sem retenção na Outbox.
 */
class SparkBlockGateway(
    private val client: SparkBackendClient?
) : BlockGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun blockUser(socialId: String): BlockOutcome<Unit> =
        post(BlockContract.BLOCKS_PATH, json.encodeToString(BlockUserRequestDto(socialId))) { body ->
            val dto = json.decodeFromString<BlockUserResponseDto>(body)
            if (dto.result == "BLOCKED") Unit else null
        }

    override suspend fun unblockUser(socialId: String): BlockOutcome<Unit> =
        delete(BlockContract.unblockPath(socialId)) { body ->
            val dto = json.decodeFromString<UnblockUserResponseDto>(body)
            if (dto.result == "UNBLOCKED") Unit else null
        }

    override suspend fun listBlockedUsers(): BlockOutcome<List<BlockedUser>> =
        get(BlockContract.BLOCKS_PATH) { body ->
            val dto = json.decodeFromString<ListBlockedUsersResponseDto>(body)
            dto.blockedUsers.map { it.toDomain() }
        }

    private suspend fun <T> post(
        path: String,
        jsonBody: String,
        parse: (String) -> T?
    ): BlockOutcome<T> {
        val activeClient = client ?: return BlockOutcome.Failure(BlockError.NOT_CONFIGURED)
        val outcome = activeClient.postJson(path, jsonBody)
        return interpret(outcome, parse)
    }

    private suspend fun <T> delete(
        path: String,
        parse: (String) -> T?
    ): BlockOutcome<T> {
        val activeClient = client ?: return BlockOutcome.Failure(BlockError.NOT_CONFIGURED)
        val outcome = activeClient.delete(path)
        return interpret(outcome, parse)
    }

    private suspend fun <T> get(
        path: String,
        parse: (String) -> T?
    ): BlockOutcome<T> {
        val activeClient = client ?: return BlockOutcome.Failure(BlockError.NOT_CONFIGURED)
        val outcome = activeClient.getJson(path)
        return interpret(outcome, parse)
    }

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): BlockOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured -> BlockOutcome.Failure(BlockError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut -> BlockOutcome.Failure(BlockError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure -> BlockOutcome.Failure(BlockError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                null
            }
            if (parsed == null) {
                BlockOutcome.Failure(BlockError.REJECTED)
            } else {
                BlockOutcome.Success(parsed)
            }
        } else {
            BlockOutcome.Failure(errorOf(outcome))
        }
    }

    private fun errorOf(outcome: SparkHttpOutcome.Response): BlockError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialContract.ErrorCodes.UNAUTHENTICATED -> BlockError.AUTH_REQUIRED
            SocialContract.ErrorCodes.AUTH_UNAVAILABLE -> BlockError.UNAVAILABLE
            SocialContract.ErrorCodes.API_RATE_LIMITED -> BlockError.RATE_LIMITED
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED,
            BlockContract.ErrorCodes.SOCIAL_NOT_ENABLED -> BlockError.SOCIAL_NOT_ENABLED
            BlockContract.ErrorCodes.SOCIAL_PROFILE_DISABLED -> BlockError.SOCIAL_DISABLED
            BlockContract.ErrorCodes.SOCIAL_PROFILE_NOT_FOUND -> BlockError.PROFILE_NOT_FOUND
            BlockContract.ErrorCodes.CANNOT_BLOCK_SELF -> BlockError.CANNOT_BLOCK_SELF
            BlockContract.ErrorCodes.BLOCK_NOT_FOUND -> BlockError.BLOCK_NOT_FOUND
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> BlockError.AUTH_REQUIRED
                outcome.code == HTTP_TOO_MANY_REQUESTS -> BlockError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> BlockError.UNAVAILABLE
                else -> BlockError.REJECTED
            }
        }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
