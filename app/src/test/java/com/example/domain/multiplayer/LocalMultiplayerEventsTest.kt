package com.example.domain.multiplayer

import com.example.data.multiplayer.OutgoingEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os eventos que saem do aparelho (T19.5 §6.4 / §9): derivados do estado, com identidade
 * determinística, e sem nada pessoal no payload.
 */
class LocalMultiplayerEventsTest {

    private val roomId = "room-1"

    private fun exercise(position: Int, exerciseSessionId: Long, vararg completed: Pair<Int, Long?>) =
        LocalExerciseProgress(
            position = position,
            canonicalExerciseId = "canonical-$position",
            exerciseSessionId = exerciseSessionId,
            setCount = 3,
            completedSets = completed.toMap()
        )

    @Test
    fun `o mesmo fato produz o mesmo eventId - e fatos diferentes, ids diferentes`() {
        val a = LocalMultiplayerEvents.setCompleted(roomId, exercise(1, 100), 1, 5_000L)
        val b = LocalMultiplayerEvents.setCompleted(roomId, exercise(1, 100), 1, 9_000L)
        assertEquals(a.eventId, b.eventId)

        assertNotEquals(a.eventId, LocalMultiplayerEvents.setCompleted(roomId, exercise(1, 100), 2, 5_000L).eventId)
        assertNotEquals(a.eventId, LocalMultiplayerEvents.setCompleted(roomId, exercise(1, 101), 1, 5_000L).eventId)
        assertNotEquals(a.eventId, LocalMultiplayerEvents.setCompleted("room-2", exercise(1, 100), 1, 5_000L).eventId)
        assertTrue(a.eventId.matches(Regex("^[A-Za-z0-9._:-]{8,64}$")))
    }

    @Test
    fun `derive comeca pelo inicio e segue a ordem de conclusao`() {
        val events = LocalMultiplayerEvents.derive(
            roomId,
            sessionId = 7,
            exercises = listOf(
                exercise(1, 100, 1 to 1_000L, 2 to 3_000L),
                exercise(2, 101, 1 to 2_000L)
            )
        )

        assertEquals(MultiplayerEventType.WORKOUT_STARTED, events.first().type)
        assertEquals(2, (events.first().payload as MultiplayerEventPayload.WorkoutStarted).exerciseCount)
        val sets = events.drop(1).map { it.payload as MultiplayerEventPayload.SetCompleted }
        assertEquals(listOf(1 to 1, 2 to 1, 1 to 2), sets.map { it.exercisePosition to it.setNumber })
        assertEquals("canonical-2", sets[1].canonicalExerciseId)
        assertEquals(3, sets[0].setCount)
    }

    @Test
    fun `derive de uma sessao sem serie concluida e so o inicio`() {
        val events = LocalMultiplayerEvents.derive(roomId, 7, listOf(exercise(1, 100)))
        assertEquals(1, events.size)
        assertEquals(MultiplayerEventType.WORKOUT_STARTED, events.single().type)
    }

    @Test
    fun `o payload serializado nao tem peso, repeticao, RPE, nota, PR, XP nem identidade local`() {
        val forbidden = listOf("weight", "reps", "repetitions", "rpe", "rir", "notes", "pr", "xp", "uid", "syncId", "sessionId", "exerciseSessionId", "localId")
        val events = LocalMultiplayerEvents.derive(roomId, 7, listOf(exercise(1, 100, 1 to 1_000L))) +
            LocalMultiplayerEvents.memberFinished(roomId, 7)
        events.forEach { event ->
            val keys = OutgoingEventDto.of(event).payload.keys.map { it.lowercase() }
            forbidden.forEach { key -> assertFalse("$key em ${event.type}", keys.contains(key.lowercase())) }
        }
        val set = OutgoingEventDto.of(events[1]).payload
        assertEquals(setOf("canonicalExerciseId", "exercisePosition", "setNumber", "setCount", "completedAt"), set.keys)
    }
}
