package com.example.data.multiplayer

import com.example.domain.multiplayer.MultiplayerBlueprintExercise
import com.example.domain.multiplayer.MultiplayerEvent
import com.example.domain.multiplayer.MultiplayerEventPayload
import com.example.domain.multiplayer.MultiplayerEventType
import com.example.domain.multiplayer.MultiplayerEventsPage
import com.example.domain.multiplayer.MultiplayerInvitation
import com.example.domain.multiplayer.MultiplayerMember
import com.example.domain.multiplayer.MultiplayerMemberRole
import com.example.domain.multiplayer.MultiplayerMemberStatus
import com.example.domain.multiplayer.MultiplayerPublishReceipt
import com.example.domain.multiplayer.MultiplayerRoom
import com.example.domain.multiplayer.MultiplayerRoomStatus
import com.example.domain.multiplayer.MultiplayerWorkoutBlueprint
import com.example.domain.multiplayer.OutgoingMultiplayerEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Os DTOs do multiplayer remoto (T19.5), espelho de `multiplayer.contract.ts`.
 *
 * O que sai do aparelho: `clientRequestId`, `inviteeSocialId`, o treino portável, e eventos com
 * `eventId`, `type` e um payload de coordenação. O que **não** tem campo aqui, e portanto não tem
 * como sair: peso, repetição, RPE, nota, PR, XP, uid, e-mail, `deviceId`, `syncId`.
 */
@Serializable
internal data class CreateRoomRequestDto(
    val clientRequestId: String,
    val inviteeSocialId: String,
    val workout: BlueprintDto
) {
    companion object {
        fun of(clientRequestId: String, inviteeSocialId: String, workout: MultiplayerWorkoutBlueprint) =
            CreateRoomRequestDto(clientRequestId, inviteeSocialId, BlueprintDto.of(workout))
    }
}

@Serializable
internal data class BlueprintDto(
    val snapshotVersion: Int = 1,
    val name: String,
    val shortIdentifier: String? = null,
    val exercises: List<BlueprintExerciseDto> = emptyList()
) {
    fun toDomain() = MultiplayerWorkoutBlueprint(
        name = name,
        shortIdentifier = shortIdentifier,
        exercises = exercises.map { it.toDomain() }
    )

    companion object {
        fun of(workout: MultiplayerWorkoutBlueprint) = BlueprintDto(
            snapshotVersion = 1,
            name = workout.name,
            shortIdentifier = workout.shortIdentifier,
            exercises = workout.exercises.map {
                BlueprintExerciseDto(
                    canonicalExerciseId = it.canonicalExerciseId,
                    sortOrder = it.sortOrder,
                    targetSets = it.targetSets,
                    minReps = it.minReps,
                    maxReps = it.maxReps,
                    restDurationSeconds = it.restDurationSeconds
                )
            }
        )
    }
}

@Serializable
internal data class BlueprintExerciseDto(
    val canonicalExerciseId: String,
    val sortOrder: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restDurationSeconds: Int
) {
    fun toDomain() = MultiplayerBlueprintExercise(
        canonicalExerciseId = canonicalExerciseId,
        sortOrder = sortOrder,
        targetSets = targetSets,
        minReps = minReps,
        maxReps = maxReps,
        restDurationSeconds = restDurationSeconds
    )
}

@Serializable
internal data class MemberDto(
    val socialId: String,
    val displayName: String = "",
    val role: String,
    val status: String,
    val connected: Boolean = false,
    val lastSeenAt: Long? = null,
    val joinedAt: Long? = null
) {
    fun toDomain() = MultiplayerMember(
        socialId = socialId,
        displayName = displayName,
        role = enumOrNull<MultiplayerMemberRole>(role) ?: MultiplayerMemberRole.GUEST,
        status = enumOrNull<MultiplayerMemberStatus>(status) ?: MultiplayerMemberStatus.LEFT,
        connected = connected,
        lastSeenAt = lastSeenAt,
        joinedAt = joinedAt
    )
}

@Serializable
internal data class MeDto(val role: String, val status: String)

@Serializable
internal data class RoomDto(
    val roomId: String,
    val status: String,
    val closeReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val hostSocialId: String = "",
    val me: MeDto,
    val members: List<MemberDto> = emptyList(),
    val workout: BlueprintDto,
    val lastSequence: Long = 0
) {
    fun toDomain() = MultiplayerRoom(
        roomId = roomId,
        status = enumOrNull<MultiplayerRoomStatus>(status) ?: MultiplayerRoomStatus.CLOSED,
        closeReason = closeReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
        hostSocialId = hostSocialId,
        myRole = enumOrNull<MultiplayerMemberRole>(me.role) ?: MultiplayerMemberRole.GUEST,
        myStatus = enumOrNull<MultiplayerMemberStatus>(me.status) ?: MultiplayerMemberStatus.LEFT,
        members = members.map { it.toDomain() },
        workout = workout.toDomain(),
        lastSequence = lastSequence
    )
}

@Serializable
internal data class InvitationHostDto(val socialId: String, val displayName: String = "")

@Serializable
internal data class InvitationDto(
    val roomId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val host: InvitationHostDto,
    val workoutName: String = "",
    val exerciseCount: Int = 0
) {
    fun toDomain() = MultiplayerInvitation(
        roomId = roomId,
        createdAt = createdAt,
        expiresAt = expiresAt,
        hostSocialId = host.socialId,
        hostDisplayName = host.displayName,
        workoutName = workoutName,
        exerciseCount = exerciseCount
    )
}

@Serializable
internal data class EventDto(
    val eventId: String,
    val sequence: Long,
    val actorSocialId: String,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val createdAt: Long = 0
) {
    fun toDomain(): MultiplayerEvent {
        val domainType = enumOrNull<MultiplayerEventType>(type) ?: MultiplayerEventType.UNKNOWN
        return MultiplayerEvent(
            eventId = eventId,
            sequence = sequence,
            actorSocialId = actorSocialId,
            type = domainType,
            payload = decodePayload(domainType, payload),
            createdAt = createdAt
        )
    }
}

@Serializable
internal data class EventsPageDto(
    val room: RoomDto,
    val events: List<EventDto> = emptyList(),
    val cursor: Long = 0,
    val hasMore: Boolean = false
) {
    fun toDomain() = MultiplayerEventsPage(
        room = room.toDomain(),
        events = events.map { it.toDomain() },
        cursor = cursor,
        hasMore = hasMore
    )
}

@Serializable
internal data class OutgoingEventDto(val eventId: String, val type: String, val payload: JsonObject) {
    companion object {
        fun of(event: OutgoingMultiplayerEvent) = OutgoingEventDto(
            eventId = event.eventId,
            type = event.type.name,
            payload = encodePayload(event.payload)
        )
    }
}

@Serializable
internal data class PublishEventsRequestDto(val events: List<OutgoingEventDto>)

@Serializable
internal data class AcceptedEventDto(val eventId: String, val sequence: Long)

@Serializable
internal data class PublishEventsResponseDto(val accepted: List<AcceptedEventDto> = emptyList(), val room: RoomDto) {
    fun toDomain() = MultiplayerPublishReceipt(
        accepted = accepted.map { it.eventId to it.sequence },
        room = room.toDomain()
    )
}

@Serializable
internal data class ActionResponseDto(val success: Boolean = false)

internal inline fun <reified E : Enum<E>> enumOrNull(value: String): E? =
    enumValues<E>().firstOrNull { it.name == value }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun decodePayload(type: MultiplayerEventType, payload: JsonObject): MultiplayerEventPayload = when (type) {
    MultiplayerEventType.WORKOUT_STARTED ->
        MultiplayerEventPayload.WorkoutStarted(exerciseCount = payload.int("exerciseCount") ?: 0)
    MultiplayerEventType.SET_COMPLETED -> {
        val position = payload.int("exercisePosition")
        val setNumber = payload.int("setNumber")
        if (position == null || setNumber == null) {
            MultiplayerEventPayload.Empty
        } else {
            MultiplayerEventPayload.SetCompleted(
                canonicalExerciseId = payload.text("canonicalExerciseId"),
                exercisePosition = position,
                setNumber = setNumber,
                setCount = payload.int("setCount") ?: setNumber,
                completedAt = payload.long("completedAt") ?: 0L
            )
        }
    }
    MultiplayerEventType.ROOM_CLOSED -> MultiplayerEventPayload.RoomClosed(reason = payload.text("reason"))
    else -> MultiplayerEventPayload.Empty
}

internal fun encodePayload(payload: MultiplayerEventPayload): JsonObject = when (payload) {
    is MultiplayerEventPayload.WorkoutStarted -> buildJsonObject {
        put("exerciseCount", JsonPrimitive(payload.exerciseCount))
    }
    is MultiplayerEventPayload.SetCompleted -> buildJsonObject {
        put("canonicalExerciseId", payload.canonicalExerciseId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("exercisePosition", JsonPrimitive(payload.exercisePosition))
        put("setNumber", JsonPrimitive(payload.setNumber))
        put("setCount", JsonPrimitive(payload.setCount))
        put("completedAt", JsonPrimitive(payload.completedAt))
    }
    is MultiplayerEventPayload.RoomClosed -> buildJsonObject {
        payload.reason?.let { put("reason", JsonPrimitive(it)) }
    }
    MultiplayerEventPayload.Empty -> JsonObject(emptyMap())
}
