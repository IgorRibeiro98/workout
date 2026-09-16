package com.example.data.multiplayer

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.data.social.ErrorEnvelopeDto
import com.example.domain.multiplayer.MultiplayerError
import com.example.domain.multiplayer.MultiplayerEventsPage
import com.example.domain.multiplayer.MultiplayerGateway
import com.example.domain.multiplayer.MultiplayerInvitation
import com.example.domain.multiplayer.MultiplayerOutcome
import com.example.domain.multiplayer.MultiplayerPublishReceipt
import com.example.domain.multiplayer.MultiplayerRoom
import com.example.domain.multiplayer.MultiplayerWorkoutBlueprint
import com.example.domain.multiplayer.OutgoingMultiplayerEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private val SUCCESS_RANGE = 200..299

/**
 * O [MultiplayerGateway] sobre o `SparkBackendClient` (T19.5).
 *
 * Um cliente, um interceptor, um lugar montando `Authorization: Bearer`. O long-poll usa a
 * variante cancelável do `GET`, para que um logout feche a conexão em vez de esperá-la.
 *
 * Nenhum log: nem `roomId`, nem `socialId`, nem corpo. O pacote de multiplayer segue a regra do
 * social — o Android não registra nada.
 */
class SparkMultiplayerGateway(
    private val client: SparkBackendClient?
) : MultiplayerGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun createRoom(
        clientRequestId: String,
        inviteeSocialId: String,
        workout: MultiplayerWorkoutBlueprint
    ): MultiplayerOutcome<MultiplayerRoom> {
        val dto = CreateRoomRequestDto.of(clientRequestId, inviteeSocialId, workout)
        return post(MultiplayerContract.ROOMS_PATH, json.encodeToString(dto)) { body ->
            json.decodeFromString<RoomDto>(body).toDomain()
        }
    }

    override suspend fun listInvitations(): MultiplayerOutcome<List<MultiplayerInvitation>> =
        get(MultiplayerContract.invitationsPath()) { body ->
            json.decodeFromString<List<InvitationDto>>(body).map { it.toDomain() }
        }

    override suspend fun getRoom(roomId: String): MultiplayerOutcome<MultiplayerRoom> =
        get(MultiplayerContract.roomPath(roomId)) { body -> json.decodeFromString<RoomDto>(body).toDomain() }

    override suspend fun join(roomId: String): MultiplayerOutcome<MultiplayerRoom> =
        post(MultiplayerContract.joinPath(roomId), "{}") { body -> json.decodeFromString<RoomDto>(body).toDomain() }

    override suspend fun leave(roomId: String): MultiplayerOutcome<Unit> =
        post(MultiplayerContract.leavePath(roomId), "{}") { body ->
            if (json.decodeFromString<ActionResponseDto>(body).success) Unit else null
        }

    override suspend fun close(roomId: String): MultiplayerOutcome<Unit> =
        post(MultiplayerContract.closePath(roomId), "{}") { body ->
            if (json.decodeFromString<ActionResponseDto>(body).success) Unit else null
        }

    override suspend fun publish(
        roomId: String,
        events: List<OutgoingMultiplayerEvent>
    ): MultiplayerOutcome<MultiplayerPublishReceipt> {
        val dto = PublishEventsRequestDto(events.map { OutgoingEventDto.of(it) })
        return post(MultiplayerContract.eventsPath(roomId), json.encodeToString(dto)) { body ->
            json.decodeFromString<PublishEventsResponseDto>(body).toDomain()
        }
    }

    override suspend fun poll(roomId: String, after: Long, waitMs: Long): MultiplayerOutcome<MultiplayerEventsPage> {
        val activeClient = client ?: return MultiplayerOutcome.Failure(MultiplayerError.NOT_CONFIGURED)
        val outcome = activeClient.getJsonCancellable(
            MultiplayerContract.pollPath(roomId, after, waitMs),
            readTimeoutSeconds = MultiplayerContract.POLL_READ_TIMEOUT_SECONDS
        )
        return interpret(outcome) { body -> json.decodeFromString<EventsPageDto>(body).toDomain() }
    }

    private suspend fun <T> post(path: String, jsonBody: String, parse: (String) -> T?): MultiplayerOutcome<T> {
        val activeClient = client ?: return MultiplayerOutcome.Failure(MultiplayerError.NOT_CONFIGURED)
        return interpret(activeClient.postJson(path, jsonBody), parse)
    }

    private suspend fun <T> get(path: String, parse: (String) -> T?): MultiplayerOutcome<T> {
        val activeClient = client ?: return MultiplayerOutcome.Failure(MultiplayerError.NOT_CONFIGURED)
        return interpret(activeClient.getJson(path), parse)
    }

    private fun <T> interpret(outcome: SparkHttpOutcome, parse: (String) -> T?): MultiplayerOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured -> MultiplayerOutcome.Failure(MultiplayerError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut -> MultiplayerOutcome.Failure(MultiplayerError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure -> MultiplayerOutcome.Failure(MultiplayerError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (parsed == null) MultiplayerOutcome.Failure(MultiplayerError.REJECTED) else MultiplayerOutcome.Success(parsed)
        } else {
            MultiplayerOutcome.Failure(errorOf(outcome))
        }
    }

    private fun errorOf(outcome: SparkHttpOutcome.Response): MultiplayerError {
        val code = runCatching { json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code }.getOrNull()
        return when (code) {
            MultiplayerContract.ErrorCodes.UNAUTHENTICATED -> MultiplayerError.AUTH_REQUIRED
            MultiplayerContract.ErrorCodes.AUTH_UNAVAILABLE -> MultiplayerError.UNAVAILABLE
            MultiplayerContract.ErrorCodes.API_RATE_LIMITED,
            MultiplayerContract.ErrorCodes.RATE_LIMITED -> MultiplayerError.RATE_LIMITED
            MultiplayerContract.ErrorCodes.SOCIAL_NOT_ENABLED -> MultiplayerError.SOCIAL_NOT_ENABLED
            MultiplayerContract.ErrorCodes.FRIENDSHIP_REQUIRED -> MultiplayerError.FRIENDSHIP_REQUIRED
            MultiplayerContract.ErrorCodes.CANNOT_INVITE_SELF -> MultiplayerError.CANNOT_INVITE_SELF
            MultiplayerContract.ErrorCodes.ROOM_NOT_FOUND -> MultiplayerError.ROOM_NOT_FOUND
            MultiplayerContract.ErrorCodes.ROOM_CLOSED -> MultiplayerError.ROOM_CLOSED
            MultiplayerContract.ErrorCodes.ROOM_EXPIRED -> MultiplayerError.ROOM_EXPIRED
            MultiplayerContract.ErrorCodes.NOT_A_MEMBER -> MultiplayerError.NOT_A_MEMBER
            MultiplayerContract.ErrorCodes.MEMBER_LEFT -> MultiplayerError.MEMBER_LEFT
            MultiplayerContract.ErrorCodes.NOT_HOST -> MultiplayerError.NOT_HOST
            MultiplayerContract.ErrorCodes.INVALID_REQUEST,
            MultiplayerContract.ErrorCodes.CONFLICT,
            MultiplayerContract.ErrorCodes.EVENT_LIMIT -> MultiplayerError.INVALID_REQUEST
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> MultiplayerError.AUTH_REQUIRED
                outcome.code == HTTP_NOT_FOUND -> MultiplayerError.ROOM_NOT_FOUND
                outcome.code == HTTP_TOO_MANY_REQUESTS -> MultiplayerError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> MultiplayerError.UNAVAILABLE
                else -> MultiplayerError.REJECTED
            }
        }
    }
}
