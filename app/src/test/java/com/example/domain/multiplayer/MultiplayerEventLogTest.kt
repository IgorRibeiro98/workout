package com.example.domain.multiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O log da sala visto do aparelho (T19.5 §6.5 / §6.6): ordem é a sequence do servidor, duplicata
 * é aplicada uma vez, lacuna pede resync em vez de aplicar fora de ordem, e o progresso do peer
 * sai só dos eventos dele.
 */
class MultiplayerEventLogTest {

    private val me = "social-me"
    private val peer = "social-peer"

    private fun set(sequence: Long, actor: String, position: Int, setNumber: Int, eventId: String = "e-$actor-$position-$setNumber") =
        MultiplayerEvent(
            eventId = eventId,
            sequence = sequence,
            actorSocialId = actor,
            type = MultiplayerEventType.SET_COMPLETED,
            payload = MultiplayerEventPayload.SetCompleted(
                canonicalExerciseId = "supino-reto-barra",
                exercisePosition = position,
                setNumber = setNumber,
                setCount = 3,
                completedAt = 1_000L * sequence
            ),
            createdAt = 1_000L * sequence
        )

    private fun system(sequence: Long, actor: String, type: MultiplayerEventType) =
        MultiplayerEvent("sys-$type-$sequence", sequence, actor, type, MultiplayerEventPayload.Empty, sequence)

    @Test
    fun `eventos em ordem sao aplicados e o progresso do peer e derivado`() {
        val log = MultiplayerEventLog(me)
        val result = log.apply(
            listOf(
                system(1, peer, MultiplayerEventType.MEMBER_JOINED),
                MultiplayerEvent("started", 2, peer, MultiplayerEventType.WORKOUT_STARTED, MultiplayerEventPayload.WorkoutStarted(2), 2),
                set(3, peer, 1, 1),
                set(4, peer, 1, 2)
            )
        )

        assertEquals(4, result.applied.size)
        assertFalse(result.gapDetected)
        assertEquals(4L, log.appliedSequence)
        assertTrue(log.peer.started)
        assertEquals(setOf(1, 2), log.peer.exercises[1]?.completedSets)
        assertEquals(1, log.peer.currentExercise?.exercisePosition)
        assertEquals(2, log.peer.completedSetCount)
    }

    @Test
    fun `fora de ordem dentro da mesma pagina e reordenado pela sequence`() {
        val log = MultiplayerEventLog(me)
        val result = log.apply(listOf(set(2, peer, 1, 2), set(1, peer, 1, 1)))

        assertEquals(listOf(1L, 2L), result.applied.map { it.sequence })
        assertFalse(result.gapDetected)
        assertEquals(2L, log.appliedSequence)
    }

    @Test
    fun `lacuna nao e aplicada - fica no buffer e pede resync`() {
        val log = MultiplayerEventLog(me)
        log.apply(listOf(set(1, peer, 1, 1)))

        // 3 chega antes de 2: nada é aplicado, e o log diz que faltou algo.
        val gap = log.apply(listOf(set(3, peer, 1, 3)))
        assertTrue(gap.gapDetected)
        assertTrue(gap.applied.isEmpty())
        assertEquals(1L, log.appliedSequence)
        assertEquals(1, log.bufferedCount)
        assertEquals(setOf(1), log.peer.exercises[1]?.completedSets)

        // O resync traz o 2; o 3 sai do buffer na ordem certa.
        val filled = log.apply(listOf(set(2, peer, 1, 2)))
        assertFalse(filled.gapDetected)
        assertEquals(listOf(2L, 3L), filled.applied.map { it.sequence })
        assertEquals(3L, log.appliedSequence)
        assertEquals(0, log.bufferedCount)
        assertEquals(setOf(1, 2, 3), log.peer.exercises[1]?.completedSets)
    }

    @Test
    fun `o mesmo evento duas vezes e aplicado uma vez - por sequence e por eventId`() {
        val log = MultiplayerEventLog(me)
        log.apply(listOf(set(1, peer, 1, 1), set(2, peer, 1, 2)))

        // A mesma página de novo (retry depois de resposta perdida).
        val replay = log.apply(listOf(set(1, peer, 1, 1), set(2, peer, 1, 2)))
        assertTrue(replay.applied.isEmpty())
        assertEquals(2L, log.appliedSequence)

        // Mesmo eventId com outra sequence (não deveria acontecer; o log não confia).
        val forged = log.apply(listOf(set(3, peer, 1, 3, eventId = "e-$peer-1-1")))
        assertTrue(forged.applied.isEmpty())
        assertEquals(3L, log.appliedSequence)
        assertEquals(setOf(1, 2), log.peer.exercises[1]?.completedSets)
    }

    @Test
    fun `os meus eventos voltam pelo log e nao viram progresso do peer`() {
        val log = MultiplayerEventLog(me)
        log.apply(listOf(set(1, me, 1, 1), set(2, me, 1, 2), set(3, peer, 2, 1)))

        assertEquals(3L, log.appliedSequence)
        assertNull(log.peer.exercises[1])
        assertEquals(setOf(1), log.peer.exercises[2]?.completedSets)
    }

    @Test
    fun `finished e left do peer, e ROOM_CLOSED encerra`() {
        val log = MultiplayerEventLog(me)
        log.apply(listOf(system(1, peer, MultiplayerEventType.MEMBER_FINISHED)))
        assertTrue(log.peer.finished)

        log.apply(listOf(system(2, peer, MultiplayerEventType.MEMBER_LEFT)))
        assertTrue(log.peer.left)

        val closed = log.apply(listOf(system(3, me, MultiplayerEventType.ROOM_CLOSED)))
        assertTrue(closed.roomClosed)
        assertTrue(log.roomClosed)
    }

    @Test
    fun `reset recomeca do zero`() {
        val log = MultiplayerEventLog(me)
        log.apply(listOf(set(1, peer, 1, 1)))
        log.reset()
        assertEquals(0L, log.appliedSequence)
        assertEquals(PeerProgress(), log.peer)
        // E a mesma sequence 1 é aplicada de novo depois do reset (resync integral).
        assertEquals(1, log.apply(listOf(set(1, peer, 1, 1))).applied.size)
    }

    @Test
    fun `tipo desconhecido e ignorado sem quebrar a ordem`() {
        val log = MultiplayerEventLog(me)
        val result = log.apply(listOf(system(1, peer, MultiplayerEventType.UNKNOWN), set(2, peer, 1, 1)))
        assertEquals(2, result.applied.size)
        assertEquals(2L, log.appliedSequence)
        assertEquals(setOf(1), log.peer.exercises[1]?.completedSets)
    }

    @Test
    fun `forExercise prefere o id canonico e cai na posicao`() {
        val log = MultiplayerEventLog(me)
        log.apply(listOf(set(1, peer, 2, 1)))
        val byCanonical = log.peer.forExercise("supino-reto-barra", position = 5)
        assertEquals(2, byCanonical?.exercisePosition)
        val byPosition = log.peer.forExercise(null, position = 2)
        assertEquals(2, byPosition?.exercisePosition)
        assertNull(log.peer.forExercise("outro", position = 9))
    }
}
