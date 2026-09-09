package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareError
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private val SUCCESS_RANGE = 200..299

class SparkWorkoutShareGateway(
    private val client: SparkBackendClient?
) : WorkoutShareGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun createShare(
        recipientSocialId: String,
        clientRequestId: String,
        snapshot: SharedWorkoutSnapshot
    ): WorkoutShareOutcome<WorkoutShareDetail> {
        val dto = CreateWorkoutShareRequestDto(
            recipientSocialId = recipientSocialId,
            clientRequestId = clientRequestId,
            snapshot = SharedWorkoutSnapshotDto.fromDomain(snapshot)
        )
        return post(WorkoutShareContract.WORKOUT_SHARES_PATH, json.encodeToString(dto)) { body ->
            json.decodeFromString<WorkoutShareDetailDto>(body).toDomain()
        }
    }

    override suspend fun listReceived(): WorkoutShareOutcome<List<WorkoutShareItem>> =
        get(WorkoutShareContract.receivedPath()) { body ->
            json.decodeFromString<List<WorkoutShareItemDto>>(body).map { it.toDomain() }
        }

    override suspend fun listSent(): WorkoutShareOutcome<List<WorkoutShareItem>> =
        get(WorkoutShareContract.sentPath()) { body ->
            json.decodeFromString<List<WorkoutShareItemDto>>(body).map { it.toDomain() }
        }

    override suspend fun getDetail(shareId: String): WorkoutShareOutcome<WorkoutShareDetail> =
        get(WorkoutShareContract.detailPath(shareId)) { body ->
            json.decodeFromString<WorkoutShareDetailDto>(body).toDomain()
        }

    override suspend fun acceptShare(shareId: String): WorkoutShareOutcome<SharedWorkoutSnapshot> =
        post(WorkoutShareContract.acceptPath(shareId), "{}") { body ->
            json.decodeFromString<SharedWorkoutSnapshotDto>(body).toDomain()
        }

    override suspend fun completeImport(shareId: String): WorkoutShareOutcome<Unit> =
        post(WorkoutShareContract.completeImportPath(shareId), "{}") { body ->
            val dto = json.decodeFromString<WorkoutShareActionResponseDto>(body)
            if (dto.success) Unit else null
        }

    override suspend fun declineShare(shareId: String): WorkoutShareOutcome<Unit> =
        post(WorkoutShareContract.declinePath(shareId), "{}") { body ->
            val dto = json.decodeFromString<WorkoutShareActionResponseDto>(body)
            if (dto.success) Unit else null
        }

    override suspend fun cancelShare(shareId: String): WorkoutShareOutcome<Unit> =
        post(WorkoutShareContract.cancelPath(shareId), "{}") { body ->
            val dto = json.decodeFromString<WorkoutShareActionResponseDto>(body)
            if (dto.success) Unit else null
        }

    private suspend fun <T> post(
        path: String,
        jsonBody: String,
        parse: (String) -> T?
    ): WorkoutShareOutcome<T> {
        val activeClient = client ?: return WorkoutShareOutcome.Failure(WorkoutShareError.NOT_CONFIGURED)
        val outcome = activeClient.postJson(path, jsonBody)
        return interpret(outcome, parse)
    }

    private suspend fun <T> get(
        path: String,
        parse: (String) -> T?
    ): WorkoutShareOutcome<T> {
        val activeClient = client ?: return WorkoutShareOutcome.Failure(WorkoutShareError.NOT_CONFIGURED)
        val outcome = activeClient.getJson(path)
        return interpret(outcome, parse)
    }

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): WorkoutShareOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured -> WorkoutShareOutcome.Failure(WorkoutShareError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut -> WorkoutShareOutcome.Failure(WorkoutShareError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure -> WorkoutShareOutcome.Failure(WorkoutShareError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                null
            }
            if (parsed == null) {
                WorkoutShareOutcome.Failure(WorkoutShareError.REJECTED)
            } else {
                WorkoutShareOutcome.Success(parsed)
            }
        } else {
            WorkoutShareOutcome.Failure(errorOf(outcome))
        }
    }

    private fun errorOf(outcome: SparkHttpOutcome.Response): WorkoutShareError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            WorkoutShareContract.ErrorCodes.UNAUTHENTICATED -> WorkoutShareError.AUTH_REQUIRED
            WorkoutShareContract.ErrorCodes.AUTH_UNAVAILABLE -> WorkoutShareError.UNAVAILABLE
            WorkoutShareContract.ErrorCodes.API_RATE_LIMITED,
            WorkoutShareContract.ErrorCodes.RATE_LIMITED -> WorkoutShareError.RATE_LIMITED
            WorkoutShareContract.ErrorCodes.SOCIAL_NOT_ENABLED -> WorkoutShareError.SOCIAL_NOT_ENABLED
            WorkoutShareContract.ErrorCodes.FRIENDSHIP_REQUIRED -> WorkoutShareError.FRIENDSHIP_REQUIRED
            WorkoutShareContract.ErrorCodes.CANNOT_SHARE_WITH_SELF -> WorkoutShareError.CANNOT_SHARE_SELF
            WorkoutShareContract.ErrorCodes.BLOCKED_USER -> WorkoutShareError.BLOCKED_USER
            WorkoutShareContract.ErrorCodes.SHARE_NOT_FOUND,
            WorkoutShareContract.ErrorCodes.RECIPIENT_NOT_FOUND -> WorkoutShareError.SHARE_NOT_FOUND
            WorkoutShareContract.ErrorCodes.INVALID_SHARE_STATE -> WorkoutShareError.INVALID_STATE
            WorkoutShareContract.ErrorCodes.INVALID_SNAPSHOT -> WorkoutShareError.INVALID_SNAPSHOT
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> WorkoutShareError.AUTH_REQUIRED
                outcome.code == HTTP_TOO_MANY_REQUESTS -> WorkoutShareError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> WorkoutShareError.UNAVAILABLE
                else -> WorkoutShareError.REJECTED
            }
        }
    }
}
