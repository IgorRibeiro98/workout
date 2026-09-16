package com.example.data.multiplayer

import com.example.data.local.SessionStatus
import com.example.data.local.SessionWithDetails
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutSessionMultiplayerLinkEntity
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.engine.WorkoutEngine
import com.example.domain.multiplayer.LocalExerciseProgress
import com.example.domain.multiplayer.LocalMultiplayerEvents
import com.example.domain.multiplayer.MultiplayerConnection
import com.example.domain.multiplayer.MultiplayerEndReason
import com.example.domain.multiplayer.MultiplayerError
import com.example.domain.multiplayer.MultiplayerEventLog
import com.example.domain.multiplayer.MultiplayerEventsPage
import com.example.domain.multiplayer.MultiplayerGateway
import com.example.domain.multiplayer.MultiplayerMemberRole
import com.example.domain.multiplayer.MultiplayerOutcome
import com.example.domain.multiplayer.MultiplayerRoom
import com.example.domain.multiplayer.MultiplayerRoomStatus
import com.example.domain.multiplayer.MultiplayerSessionState
import com.example.domain.multiplayer.OutgoingMultiplayerEvent
import com.example.domain.multiplayer.PeerProgress
import com.example.domain.multiplayer.PeerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * O runtime do treino em dupla à distância neste aparelho (T19.5).
 *
 * ```text
 * execução local (Room)  →  evento canônico  →  sala (servidor)  →  outro aparelho  →  tela dele
 * ```
 *
 * ## O que ele é
 *
 * Um observador da sessão ativa. Enquanto a sessão ativa for `DUO_REMOTE`, vinculada a uma sala
 * **da conta atual**, ele:
 *
 * 1. lê a sala do zero (`after = 0`) e reduz o log com [MultiplayerEventLog];
 * 2. publica os eventos derivados do Room ([LocalMultiplayerEvents.derive]) que ainda não foram
 *    confirmados — e republica **todos** depois de uma reconexão, porque reenviar é dedupe;
 * 3. fica em long-poll, aplicando o que chega em ordem e reconectando com backoff quando a rede
 *    cai;
 * 4. expõe um [MultiplayerSessionState] para a tela.
 *
 * ## O que ele nunca faz
 *
 * Escrever no Room por causa do servidor. Nenhum evento recebido altera série, peso, repetição,
 * descanso, PR, XP ou o status da sessão. O peer é **visto**, nunca aplicado. Se este coordenador
 * for desligado no meio do treino, a sessão local não percebe.
 *
 * ## Escopo por conta
 *
 * O laço é relançado a cada mudança de `(uid, vínculo)`. Trocar de conta cancela o laço da conta
 * anterior — inclusive o `GET` em voo, que é cancelável — e a conta nova encontra um vínculo de
 * outra conta: estado [MultiplayerEndReason.OTHER_ACCOUNT], sem nenhuma chamada. Nenhum evento,
 * cursor ou sala da conta anterior chega à nova, porque tudo isso vive dentro do laço cancelado.
 */
class MultiplayerSessionCoordinator(
    private val engine: WorkoutEngine,
    private val dao: WorkoutDao,
    private val gateway: MultiplayerGateway,
    private val authGateway: AuthGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pollWaitMs: Long = MultiplayerContract.POLL_WAIT_MS,
    private val reconnectBackoffMs: List<Long> = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
    /** Pausa depois de um poll vazio: um servidor que responda na hora não pode virar um laço quente. */
    private val minPollIntervalMs: Long = 1_000L
) {

    private val _state = MutableStateFlow<MultiplayerSessionState?>(null)
    val state: StateFlow<MultiplayerSessionState?> = _state.asStateFlow()

    private val finalizeMutex = Mutex()
    private var job: Job? = null

    /** A sessão ativa com uma sala (T19.5), só o que o laço precisa saber para decidir relançar. */
    private data class Binding(val uid: String?, val link: WorkoutSessionMultiplayerLinkEntity?)

    fun start() {
        if (job != null) return
        job = scope.launch {
            val uidFlow = authGateway.state.map { (it as? AuthState.SignedIn)?.account?.uid }.distinctUntilChanged()
            combine(uidFlow, engine.activeMultiplayerLinkFlow) { uid, link -> Binding(uid, link) }
                .distinctUntilChanged { old, new ->
                    old.uid == new.uid &&
                        old.link?.sessionId == new.link?.sessionId &&
                        old.link?.finishedNotifiedAt == new.link?.finishedNotifiedAt
                }
                .collectLatest { binding ->
                    val uid = binding.uid
                    val link = binding.link
                    if (uid != null) finalizeEndedSessions(uid)
                    when {
                        link == null || uid == null -> _state.value = null
                        link.accountUid != uid -> _state.value = MultiplayerSessionState(
                            roomId = link.roomId,
                            role = roleOf(link),
                            connection = MultiplayerConnection.ENDED,
                            endReason = MultiplayerEndReason.OTHER_ACCOUNT
                        )
                        link.finishedNotifiedAt != null -> _state.value = MultiplayerSessionState(
                            roomId = link.roomId,
                            role = roleOf(link),
                            connection = MultiplayerConnection.ENDED,
                            endReason = MultiplayerEndReason.LEFT
                        )
                        !gateway.isConfigured -> _state.value = MultiplayerSessionState(
                            roomId = link.roomId,
                            role = roleOf(link),
                            connection = MultiplayerConnection.ENDED,
                            endReason = MultiplayerEndReason.NOT_CONFIGURED
                        )
                        else -> runRoom(uid, link)
                    }
                }
        }
    }

    /**
     * Sai da sala e continua o treino. O vínculo é marcado como encerrado (`finishedNotifiedAt`)
     * para que nenhuma reconexão automática volte a entrar — sair é decisão explícita (§10).
     */
    suspend fun leaveRoom() {
        val current = _state.value ?: return
        val link = dao.getMultiplayerLinkForSession(currentSessionId() ?: return) ?: return
        if (link.roomId != current.roomId) return
        val outcome = gateway.leave(link.roomId)
        if (outcome is MultiplayerOutcome.Failure && outcome.error.isTransient) {
            _state.value = current.copy(connection = MultiplayerConnection.RECONNECTING)
            return
        }
        dao.markMultiplayerLinkFinishedNotified(link.sessionId, clock())
    }

    private suspend fun currentSessionId(): Long? = dao.getActiveSession()?.id

    private fun roleOf(link: WorkoutSessionMultiplayerLinkEntity): MultiplayerMemberRole =
        MultiplayerMemberRole.entries.firstOrNull { it.name == link.role } ?: MultiplayerMemberRole.GUEST

    // ------------------------------------------------------------------ o laço de uma sala

    private class RoomRun(
        val uid: String,
        val link: WorkoutSessionMultiplayerLinkEntity,
        val role: MultiplayerMemberRole
    ) {
        var log: MultiplayerEventLog? = null
        var room: MultiplayerRoom? = null
        val sentEventIds = HashSet<String>()
        var pending: List<OutgoingMultiplayerEvent> = emptyList()
        val canonicalIds = HashMap<Long, String?>()
        val publishSignal = Channel<Unit>(Channel.CONFLATED)
    }

    private suspend fun runRoom(uid: String, link: WorkoutSessionMultiplayerLinkEntity) {
        val run = RoomRun(uid, link, roleOf(link))
        _state.value = MultiplayerSessionState(
            roomId = link.roomId,
            role = run.role,
            connection = MultiplayerConnection.CONNECTING
        )

        coroutineScope {
            // O publicador: a cada mudança da sessão local (série concluída), deriva os eventos e
            // publica os que ainda não foram confirmados. Nunca decide o estado da conexão sozinho;
            // uma falha aqui deixa os eventos pendentes, e o laço de poll é quem reconecta.
            launch {
                engine.activeSessionWithDetailsFlow
                    .map { details -> details?.takeIf { it.session.id == link.sessionId }?.let { localProgress(run, it) } }
                    .distinctUntilChanged()
                    .collectLatest { progress ->
                        run.pending = if (progress == null) emptyList() else LocalMultiplayerEvents.derive(link.roomId, link.sessionId, progress)
                        run.publishSignal.trySend(Unit)
                    }
            }
            launch {
                for (signal in run.publishSignal) {
                    publishPending(run)
                }
            }
            pollLoop(run)
        }
    }

    private suspend fun localProgress(run: RoomRun, details: SessionWithDetails): List<LocalExerciseProgress> =
        details.sortedExercises.mapIndexed { index, exercise ->
            val entity = exercise.exerciseSession
            val exerciseId = entity.actualExerciseId ?: entity.plannedExerciseId
            val canonicalId = if (exerciseId == null) {
                null
            } else {
                run.canonicalIds.getOrPut(exerciseId) { dao.getExerciseById(exerciseId)?.canonicalId?.takeIf { it.isNotBlank() } }
            }
            LocalExerciseProgress(
                position = index + 1,
                canonicalExerciseId = canonicalId,
                exerciseSessionId = entity.id,
                setCount = exercise.sets.size,
                completedSets = exercise.sets.filter { it.completed }.associate { it.setNumber to it.finishedAt }
            )
        }

    private suspend fun publishPending(run: RoomRun) {
        val current = _state.value ?: return
        if (current.connection == MultiplayerConnection.ENDED) return
        val batch = run.pending.filter { it.eventId !in run.sentEventIds }
        updatePending(run)
        if (batch.isEmpty()) return

        batch.chunked(MAX_BATCH).forEach { chunk ->
            when (val outcome = gateway.publish(run.link.roomId, chunk)) {
                is MultiplayerOutcome.Success -> {
                    outcome.data.accepted.forEach { (eventId, _) -> run.sentEventIds.add(eventId) }
                    applyRoom(run, outcome.data.room)
                }
                is MultiplayerOutcome.Failure -> {
                    if (outcome.error.isTerminal) end(run, outcome.error)
                    return
                }
            }
        }
        updatePending(run)
    }

    private fun updatePending(run: RoomRun) {
        val current = _state.value ?: return
        val pending = run.pending.count { it.eventId !in run.sentEventIds }
        if (current.pendingLocalEvents != pending) _state.value = current.copy(pendingLocalEvents = pending)
    }

    private suspend fun pollLoop(run: RoomRun) {
        var attempt = 0
        var needsResync = true
        while (true) {
            if (needsResync) {
                run.log = null
                run.sentEventIds.clear()
            }
            val after = if (needsResync) 0L else run.log?.appliedSequence ?: 0L
            val wait = if (needsResync) 0L else pollWaitMs
            when (val outcome = gateway.poll(run.link.roomId, after, wait)) {
                is MultiplayerOutcome.Success -> {
                    attempt = 0
                    val page = outcome.data
                    val wasResync = needsResync
                    needsResync = false
                    val gap = applyPage(run, page)
                    if (gap) {
                        needsResync = true
                        continue
                    }
                    if (wasResync) run.publishSignal.trySend(Unit)
                    if (_state.value?.connection == MultiplayerConnection.ENDED) return
                    if (page.hasMore) continue
                    if (page.events.isEmpty()) delay(minPollIntervalMs)
                }
                is MultiplayerOutcome.Failure -> {
                    val error = outcome.error
                    if (error.isTerminal || error == MultiplayerError.AUTH_REQUIRED ||
                        error == MultiplayerError.NOT_CONFIGURED || !error.isTransient
                    ) {
                        end(run, error)
                        return
                    }
                    _state.value?.let { _state.value = it.copy(connection = MultiplayerConnection.RECONNECTING) }
                    delay(reconnectBackoffMs[minOf(attempt, reconnectBackoffMs.lastIndex)])
                    attempt += 1
                    needsResync = true
                }
            }
        }
    }

    /** Aplica uma página ao log e ao estado. Devolve `true` quando o log pediu resync (lacuna). */
    private fun applyPage(run: RoomRun, page: MultiplayerEventsPage): Boolean {
        val mySocialId = page.room.members.firstOrNull { it.role == page.room.myRole }?.socialId.orEmpty()
        val log = run.log ?: MultiplayerEventLog(mySocialId).also { run.log = it }
        val result = log.apply(page.events)
        applyRoom(run, page.room, connected = true)
        if (result.roomClosed || !page.room.isOpen) {
            end(run, if (page.room.status == MultiplayerRoomStatus.EXPIRED) MultiplayerError.ROOM_EXPIRED else MultiplayerError.ROOM_CLOSED)
            return false
        }
        return result.gapDetected
    }

    private fun applyRoom(run: RoomRun, room: MultiplayerRoom, connected: Boolean = false) {
        run.room = room
        val current = _state.value ?: return
        if (current.connection == MultiplayerConnection.ENDED) return
        val peer = room.peer
        _state.value = current.copy(
            connection = if (connected) MultiplayerConnection.CONNECTED else current.connection,
            room = room,
            peer = peer?.let {
                PeerView(
                    displayName = it.displayName,
                    status = it.status,
                    connected = it.connected,
                    progress = run.log?.peer ?: PeerProgress()
                )
            },
            lastSyncAt = if (connected) clock() else current.lastSyncAt,
            pendingLocalEvents = run.pending.count { e -> e.eventId !in run.sentEventIds }
        )
    }

    private fun end(run: RoomRun, error: MultiplayerError) {
        val current = _state.value ?: return
        val reason = when (error) {
            MultiplayerError.ROOM_CLOSED -> MultiplayerEndReason.ROOM_CLOSED
            MultiplayerError.ROOM_EXPIRED -> MultiplayerEndReason.ROOM_EXPIRED
            MultiplayerError.ROOM_NOT_FOUND -> MultiplayerEndReason.ROOM_NOT_FOUND
            MultiplayerError.MEMBER_LEFT -> MultiplayerEndReason.LEFT
            MultiplayerError.AUTH_REQUIRED -> MultiplayerEndReason.AUTH_REQUIRED
            MultiplayerError.NOT_CONFIGURED -> MultiplayerEndReason.NOT_CONFIGURED
            else -> MultiplayerEndReason.REJECTED
        }
        _state.value = current.copy(
            connection = MultiplayerConnection.ENDED,
            endReason = reason,
            room = run.room ?: current.room
        )
    }

    // ------------------------------------------------------------------ fim da sessão

    /**
     * Avisa a sala das sessões desta conta que já terminaram sem que a sala soubesse: concluída →
     * `MEMBER_FINISHED`; cancelada → sair. Roda a cada mudança de vínculo (inclusive quando a
     * sessão acaba e o vínculo ativo vira `null`) e na abertura do app — é assim que uma morte de
     * processo entre "concluir" e "avisar" não deixa o peer esperando para sempre.
     *
     * Falha transitória deixa o vínculo como está, para a próxima passagem; falha terminal (sala
     * já fechada, conta sem acesso) marca como avisado — não há mais a quem avisar.
     */
    private suspend fun finalizeEndedSessions(uid: String) = finalizeMutex.withLock {
        if (!gateway.isConfigured) return@withLock
        val links = runCatching { dao.getUnnotifiedFinishedMultiplayerLinks(uid) }.getOrDefault(emptyList())
        for (link in links) {
            val session = dao.getSessionById(link.sessionId) ?: continue
            val outcome = if (session.status == SessionStatus.COMPLETED.name) {
                gateway.publish(link.roomId, listOf(LocalMultiplayerEvents.memberFinished(link.roomId, link.sessionId)))
            } else {
                gateway.leave(link.roomId)
            }
            val settled = when (outcome) {
                is MultiplayerOutcome.Success -> true
                is MultiplayerOutcome.Failure -> !outcome.error.isTransient && outcome.error != MultiplayerError.AUTH_REQUIRED
            }
            if (settled) dao.markMultiplayerLinkFinishedNotified(link.sessionId, clock())
        }
    }

    companion object {
        const val MAX_BATCH = 50
    }
}
