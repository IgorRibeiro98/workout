package com.example.domain.multiplayer

import java.util.TreeMap

/**
 * O progresso do **outro** participante, derivado só do log da sala (T19.5).
 *
 * É a única coisa que o aparelho sabe sobre o treino do peer — e é tudo o que ele precisa saber:
 * começou, em que exercício está, quais séries concluiu, terminou, saiu. Nenhum peso, nenhuma
 * repetição, nenhum PR chega aqui, porque nenhum atravessa a rede.
 */
data class PeerExerciseProgress(
    val exercisePosition: Int,
    val canonicalExerciseId: String?,
    val setCount: Int,
    val completedSets: Set<Int>
) {
    val completedCount: Int get() = completedSets.size
    val isComplete: Boolean get() = setCount > 0 && completedSets.size >= setCount
}

data class PeerProgress(
    val started: Boolean = false,
    val finished: Boolean = false,
    val left: Boolean = false,
    /** Por posição do exercício na execução do peer (1-based). */
    val exercises: Map<Int, PeerExerciseProgress> = emptyMap(),
    val lastSetCompletedAt: Long? = null
) {
    /** O exercício em que o peer está: o primeiro com série pendente, ou o último tocado. */
    val currentExercise: PeerExerciseProgress?
        get() = exercises.values.sortedBy { it.exercisePosition }.let { ordered ->
            ordered.firstOrNull { !it.isComplete } ?: ordered.lastOrNull()
        }

    val completedSetCount: Int get() = exercises.values.sumOf { it.completedCount }

    fun forExercise(canonicalExerciseId: String?, position: Int): PeerExerciseProgress? =
        exercises.values.firstOrNull { canonicalExerciseId != null && it.canonicalExerciseId == canonicalExerciseId }
            ?: exercises[position]
}

/**
 * O resultado de entregar uma página de eventos ao log.
 *
 * [applied] são os eventos que entraram, em ordem; [gapDetected] diz que chegou uma sequence à
 * frente da esperada — o que ficou no buffer não é aplicado, e o coordenador refaz a leitura a
 * partir de [MultiplayerEventLog.appliedSequence] antes de continuar.
 */
data class ApplyResult(
    val applied: List<MultiplayerEvent>,
    val roomClosed: Boolean,
    val gapDetected: Boolean
)

/**
 * O log de uma sala, como este aparelho o vê (T19.5 §6.5 / §6.6).
 *
 * ## Ordering
 *
 * A ordem é a `sequence` do servidor, e só ela. Um evento só é aplicado quando é **o próximo**
 * (`sequence == appliedSequence + 1`); um que chegue à frente fica no buffer, e a lacuna é sinal
 * de resync — nunca de "aplica assim mesmo". Um que chegue atrás (já aplicado) é descartado.
 *
 * ## Deduplicação
 *
 * Dois critérios, os dois necessários: `sequence <= appliedSequence` (já passou por aqui) e
 * `eventId` já visto (o servidor nunca dá duas sequences ao mesmo `eventId`, mas o log não confia
 * nisso). Aplicado uma vez, e só uma.
 *
 * ## O que ele deriva
 *
 * O progresso do peer ([peer]) — só dos eventos cujo `actorSocialId` não é o meu. Os meus voltam
 * pelo log (é o mesmo log para os dois) e são ignorados aqui: o meu estado é o Room, não o servidor.
 *
 * Puro e síncrono: sem corrotina, sem relógio, sem rede. É o que os testes de ordering, duplicata
 * e lacuna exercitam diretamente.
 */
class MultiplayerEventLog(private val mySocialId: String) {

    var appliedSequence: Long = 0
        private set

    var peer: PeerProgress = PeerProgress()
        private set

    var roomClosed: Boolean = false
        private set

    private val appliedEventIds = HashSet<String>()
    private val buffer = TreeMap<Long, MultiplayerEvent>()

    val bufferedCount: Int get() = buffer.size

    /** Recomeça do zero — o que o coordenador faz ao reconectar com `after = 0`. */
    fun reset() {
        appliedSequence = 0
        peer = PeerProgress()
        roomClosed = false
        appliedEventIds.clear()
        buffer.clear()
    }

    fun apply(events: List<MultiplayerEvent>): ApplyResult {
        val applied = mutableListOf<MultiplayerEvent>()
        var gap = false

        for (event in events.sortedBy { it.sequence }) {
            if (event.sequence <= appliedSequence) continue
            buffer[event.sequence] = event
        }

        while (true) {
            val next = buffer.firstEntry() ?: break
            if (next.key != appliedSequence + 1) {
                gap = true
                break
            }
            buffer.pollFirstEntry()
            val event = next.value
            // A sequence avança sempre — senão um `eventId` repetido viraria uma lacuna eterna;
            // o **estado** só muda na primeira vez que o `eventId` passa por aqui.
            if (appliedEventIds.add(event.eventId)) {
                reduce(event)
                applied.add(event)
            }
            appliedSequence = event.sequence
        }

        return ApplyResult(applied = applied, roomClosed = roomClosed, gapDetected = gap)
    }

    private fun reduce(event: MultiplayerEvent) {
        if (event.type == MultiplayerEventType.ROOM_CLOSED) {
            roomClosed = true
            return
        }
        if (event.actorSocialId == mySocialId) return

        peer = when (event.type) {
            MultiplayerEventType.MEMBER_JOINED -> peer.copy(left = false)
            MultiplayerEventType.WORKOUT_STARTED -> peer.copy(started = true)
            MultiplayerEventType.SET_COMPLETED -> {
                val payload = event.payload as? MultiplayerEventPayload.SetCompleted ?: return
                val current = peer.exercises[payload.exercisePosition]
                val updated = PeerExerciseProgress(
                    exercisePosition = payload.exercisePosition,
                    canonicalExerciseId = payload.canonicalExerciseId ?: current?.canonicalExerciseId,
                    setCount = maxOf(payload.setCount, current?.setCount ?: 0),
                    completedSets = (current?.completedSets ?: emptySet()) + payload.setNumber
                )
                peer.copy(
                    started = true,
                    exercises = peer.exercises + (payload.exercisePosition to updated),
                    lastSetCompletedAt = payload.completedAt
                )
            }
            MultiplayerEventType.MEMBER_FINISHED -> peer.copy(finished = true)
            MultiplayerEventType.MEMBER_LEFT -> peer.copy(left = true)
            MultiplayerEventType.ROOM_CLOSED, MultiplayerEventType.UNKNOWN -> peer
        }
    }
}
