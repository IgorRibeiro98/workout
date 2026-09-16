package com.example.domain.multiplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Um servidor de multiplayer em memória, com as regras que o aparelho depende (T19.5).
 *
 * Sequence atribuída por sala, dedupe por `eventId`, membership por `socialId`, e um interruptor
 * de falha ([failing]) para simular rede fora. Vive em `src/test`, sem HTTP.
 */
class FakeMultiplayerGateway(
    private val mySocialId: String = "social-me",
    private val myDisplayName: String = "Eu",
    override val isConfigured: Boolean = true
) : MultiplayerGateway {

    private val mutex = Mutex()
    private val events = mutableMapOf<String, MutableList<MultiplayerEvent>>()
    private val rooms = mutableMapOf<String, MultiplayerRoom>()

    /** Quando `true`, toda chamada falha com [failure]. */
    @Volatile var failing: Boolean = false
    @Volatile var failure: MultiplayerError = MultiplayerError.NETWORK

    /** O que o próximo `join` responde, quando não for a sala em memória. */
    var joinOverride: MultiplayerOutcome<MultiplayerRoom>? = null

    val publishedBatches = mutableListOf<List<OutgoingMultiplayerEvent>>()
    val leaveCalls = mutableListOf<String>()
    val pollCalls = mutableListOf<Pair<Long, Long>>()
    var invitations: List<MultiplayerInvitation> = emptyList()

    /** Um poll que deve ficar suspenso até [releasePoll] — para testar cancelamento. */
    var holdPolls: Boolean = false
    private var heldPoll: CompletableDeferred<Unit>? = null

    fun seedRoom(
        roomId: String,
        myRole: MultiplayerMemberRole = MultiplayerMemberRole.HOST,
        peerSocialId: String = "social-peer",
        peerDisplayName: String = "João",
        peerStatus: MultiplayerMemberStatus = MultiplayerMemberStatus.ACTIVE,
        status: MultiplayerRoomStatus = MultiplayerRoomStatus.ACTIVE
    ): MultiplayerRoom {
        val room = MultiplayerRoom(
            roomId = roomId,
            status = status,
            closeReason = null,
            createdAt = 1L,
            updatedAt = 1L,
            expiresAt = Long.MAX_VALUE,
            hostSocialId = if (myRole == MultiplayerMemberRole.HOST) mySocialId else peerSocialId,
            myRole = myRole,
            myStatus = MultiplayerMemberStatus.ACTIVE,
            members = listOf(
                MultiplayerMember(mySocialId, myDisplayName, myRole, MultiplayerMemberStatus.ACTIVE, true, 1L, 1L),
                MultiplayerMember(
                    peerSocialId,
                    peerDisplayName,
                    if (myRole == MultiplayerMemberRole.HOST) MultiplayerMemberRole.GUEST else MultiplayerMemberRole.HOST,
                    peerStatus,
                    peerStatus == MultiplayerMemberStatus.ACTIVE,
                    1L,
                    1L
                )
            ),
            workout = MultiplayerWorkoutBlueprint(
                name = "Treino A",
                shortIdentifier = "A",
                exercises = listOf(MultiplayerBlueprintExercise("supino-reto-barra", 0, 3, 8, 12, 90))
            ),
            lastSequence = 0
        )
        rooms[roomId] = room
        events[roomId] = mutableListOf()
        return room
    }

    /** O peer publica um evento — o que o outro aparelho faria. */
    suspend fun peerPublishes(roomId: String, type: MultiplayerEventType, payload: MultiplayerEventPayload, eventId: String = "peer-${type}-${events[roomId]?.size}") =
        mutex.withLock { append(roomId, "social-peer", eventId, type, payload) }

    suspend fun closeRoom(roomId: String, reason: String = "HOST_CLOSED") = mutex.withLock {
        rooms[roomId] = rooms.getValue(roomId).copy(status = MultiplayerRoomStatus.CLOSED, closeReason = reason)
        append(roomId, "social-peer", "sys-closed", MultiplayerEventType.ROOM_CLOSED, MultiplayerEventPayload.RoomClosed(reason))
    }

    fun eventsOf(roomId: String): List<MultiplayerEvent> = events[roomId].orEmpty().toList()

    fun releasePoll() {
        heldPoll?.complete(Unit)
    }

    private fun append(roomId: String, actor: String, eventId: String, type: MultiplayerEventType, payload: MultiplayerEventPayload): MultiplayerEvent {
        val log = events.getValue(roomId)
        log.firstOrNull { it.eventId == eventId }?.let { return it }
        val event = MultiplayerEvent(eventId, (log.size + 1).toLong(), actor, type, payload, log.size + 1L)
        log.add(event)
        rooms[roomId] = rooms.getValue(roomId).copy(lastSequence = event.sequence)
        return event
    }

    private fun <T> failIfNeeded(): MultiplayerOutcome<T>? =
        if (failing) MultiplayerOutcome.Failure(failure) else null

    override suspend fun createRoom(clientRequestId: String, inviteeSocialId: String, workout: MultiplayerWorkoutBlueprint): MultiplayerOutcome<MultiplayerRoom> {
        failIfNeeded<MultiplayerRoom>()?.let { return it }
        val room = seedRoom("room-$clientRequestId", MultiplayerMemberRole.HOST, peerSocialId = inviteeSocialId, peerStatus = MultiplayerMemberStatus.INVITED, status = MultiplayerRoomStatus.WAITING)
        return MultiplayerOutcome.Success(room.copy(workout = workout))
    }

    override suspend fun listInvitations(): MultiplayerOutcome<List<MultiplayerInvitation>> {
        failIfNeeded<List<MultiplayerInvitation>>()?.let { return it }
        return MultiplayerOutcome.Success(invitations)
    }

    override suspend fun getRoom(roomId: String): MultiplayerOutcome<MultiplayerRoom> {
        failIfNeeded<MultiplayerRoom>()?.let { return it }
        return rooms[roomId]?.let { MultiplayerOutcome.Success(it) } ?: MultiplayerOutcome.Failure(MultiplayerError.ROOM_NOT_FOUND)
    }

    override suspend fun join(roomId: String): MultiplayerOutcome<MultiplayerRoom> {
        failIfNeeded<MultiplayerRoom>()?.let { return it }
        joinOverride?.let { return it }
        val room = rooms[roomId] ?: return MultiplayerOutcome.Failure(MultiplayerError.ROOM_NOT_FOUND)
        return MultiplayerOutcome.Success(room)
    }

    override suspend fun leave(roomId: String): MultiplayerOutcome<Unit> {
        failIfNeeded<Unit>()?.let { return it }
        leaveCalls += roomId
        return MultiplayerOutcome.Success(Unit)
    }

    override suspend fun close(roomId: String): MultiplayerOutcome<Unit> {
        failIfNeeded<Unit>()?.let { return it }
        return MultiplayerOutcome.Success(Unit)
    }

    override suspend fun publish(roomId: String, events: List<OutgoingMultiplayerEvent>): MultiplayerOutcome<MultiplayerPublishReceipt> {
        failIfNeeded<MultiplayerPublishReceipt>()?.let { return it }
        return mutex.withLock {
            val room = rooms[roomId] ?: return MultiplayerOutcome.Failure(MultiplayerError.ROOM_NOT_FOUND)
            if (room.status != MultiplayerRoomStatus.ACTIVE && room.status != MultiplayerRoomStatus.WAITING) {
                return MultiplayerOutcome.Failure(MultiplayerError.ROOM_CLOSED)
            }
            publishedBatches += events
            val accepted = events.map { event ->
                event.eventId to append(roomId, mySocialId, event.eventId, event.type, event.payload).sequence
            }
            MultiplayerOutcome.Success(MultiplayerPublishReceipt(accepted, rooms.getValue(roomId)))
        }
    }

    override suspend fun poll(roomId: String, after: Long, waitMs: Long): MultiplayerOutcome<MultiplayerEventsPage> {
        pollCalls += after to waitMs
        if (holdPolls) {
            val gate = CompletableDeferred<Unit>().also { heldPoll = it }
            gate.await()
        }
        failIfNeeded<MultiplayerEventsPage>()?.let { return it }
        val room = rooms[roomId] ?: return MultiplayerOutcome.Failure(MultiplayerError.ROOM_NOT_FOUND)
        val page = mutex.withLock { events.getValue(roomId).filter { it.sequence > after } }
        return MultiplayerOutcome.Success(
            MultiplayerEventsPage(room = room, events = page, cursor = page.lastOrNull()?.sequence ?: after, hasMore = false)
        )
    }
}
