package com.example.domain.workout.execution

import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutGuestSetLogEntity
import com.example.data.local.WorkoutParticipantRole
import com.example.data.local.WorkoutSessionParticipantEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regra de alternância da dupla (T19.4 §6.2 / §6.5), sem banco e sem tela.
 *
 * O que está fixado aqui é a ordem — dono, convidado, dono, convidado — sobre a **mesma** série,
 * e que a vez é reconstruída inteira a partir das séries persistidas: é isso que faz uma morte de
 * processo entre a série do dono e a do convidado recomeçar na vez certa.
 */
class DuoTurnResolverTest {

    private val owner = WorkoutSessionParticipantEntity(id = 1, sessionId = 10, role = "OWNER", position = 0)
    private val guest = WorkoutSessionParticipantEntity(id = 2, sessionId = 10, role = "GUEST", displayName = "João", position = 1)

    private fun exercise(vararg ownerCompleted: Boolean): ExerciseSessionWithSets = ExerciseSessionWithSets(
        exerciseSession = ExerciseSessionEntity(id = 100, sessionId = 10, plannedExerciseId = 1, actualExerciseId = 1, exerciseNameSnapshot = "Supino"),
        sets = ownerCompleted.mapIndexed { index, completed ->
            SetLogEntity(id = 1000L + index, exerciseSessionId = 100, setNumber = index + 1, weight = 60f, repetitions = 10, completed = completed)
        }
    )

    private fun guestSets(vararg completed: Boolean): List<WorkoutGuestSetLogEntity> =
        completed.mapIndexed { index, done ->
            WorkoutGuestSetLogEntity(id = 2000L + index, participantId = 2, exerciseSessionId = 100, setNumber = index + 1, completed = done)
        }

    private fun duo(guestSets: List<WorkoutGuestSetLogEntity>) = DuoExecution.from(listOf(owner, guest), guestSets)!!

    @Test
    fun `o dono abre a primeira serie`() {
        val turn = duo(guestSets(false, false)).turnFor(exercise(false, false))!!

        assertEquals(WorkoutParticipantRole.OWNER, turn.role)
        assertEquals(1, turn.setNumber)
        assertEquals(0, turn.setIndex)
        assertEquals(WorkoutParticipantRole.GUEST, turn.nextRole)
        assertNotNull(turn.ownerSet)
        assertNull(turn.guestSet)
        assertFalse(turn.isLastTurnOfExercise)
    }

    @Test
    fun `depois do dono vem o convidado na mesma serie`() {
        val turn = duo(guestSets(false, false)).turnFor(exercise(true, false))!!

        assertEquals(WorkoutParticipantRole.GUEST, turn.role)
        assertEquals(1, turn.setNumber)
        assertEquals(WorkoutParticipantRole.OWNER, turn.nextRole)
        assertEquals(2000L, turn.guestSet!!.id)
    }

    @Test
    fun `com os dois concluidos na serie 1 a vez e do dono na serie 2`() {
        val turn = duo(guestSets(true, false)).turnFor(exercise(true, false))!!

        assertEquals(WorkoutParticipantRole.OWNER, turn.role)
        assertEquals(2, turn.setNumber)
        assertEquals(1, turn.setIndex)
        assertEquals(WorkoutParticipantRole.GUEST, turn.nextRole)
    }

    @Test
    fun `a ultima vez do exercicio e a do convidado na ultima serie`() {
        val turn = duo(guestSets(true, false)).turnFor(exercise(true, true))!!

        assertEquals(WorkoutParticipantRole.GUEST, turn.role)
        assertEquals(2, turn.setNumber)
        assertNull(turn.nextRole)
        assertTrue(turn.isLastTurnOfExercise)
    }

    @Test
    fun `a ultima serie do dono nao e a ultima vez do exercicio`() {
        val turn = duo(guestSets(true, false)).turnFor(exercise(true, false))!!

        // O dono conclui a série 2 mas o convidado ainda vem: "concluir treino" seria mentira.
        assertEquals(WorkoutParticipantRole.OWNER, turn.role)
        assertFalse(turn.isLastTurnOfExercise)
    }

    @Test
    fun `exercicio concluido pelos dois nao tem vez`() {
        val duo = duo(guestSets(true, true))
        val exercise = exercise(true, true)

        assertNull(duo.turnFor(exercise))
        assertFalse(duo.hasPendingGuestSets(exercise))
    }

    @Test
    fun `dono concluiu tudo mas convidado nao o exercicio continua pendente`() {
        val duo = duo(guestSets(true, false))
        val exercise = exercise(true, true)

        assertTrue(duo.hasPendingGuestSets(exercise))
        assertEquals(WorkoutParticipantRole.GUEST, duo.turnFor(exercise)!!.role)
    }

    @Test
    fun `serie do dono sem espelho do convidado conta como pendente para o convidado`() {
        // Sem linha para a série 2 (não acontece pelo caminho normal, mas a ausência nunca pode
        // fazer uma série "pular"): o convidado ainda tem a série 2, e a vez dele nasce com uma
        // linha transitória (`id = 0`) copiando a carga do dono.
        val duo = duo(guestSets(true))
        val exercise = exercise(true, true)

        assertTrue(duo.hasPendingGuestSets(exercise))
        val turn = duo.turnFor(exercise)!!
        assertEquals(WorkoutParticipantRole.GUEST, turn.role)
        assertEquals(2, turn.setNumber)
        assertEquals(0L, turn.guestSet!!.id)
        assertEquals(60f, turn.guestSet!!.weight, 0.01f)
        assertEquals(guest.id, turn.guestSet!!.participantId)
    }

    @Test
    fun `desfazer a serie do dono devolve a vez a ele mesmo com o convidado concluido`() {
        // O dono desmarca a série 1 depois de o convidado ter feito a dele: a menor série pendente
        // volta a ser a 1, e dentro dela o dono vem primeiro.
        val turn = duo(guestSets(true, false)).turnFor(exercise(false, false))!!

        assertEquals(WorkoutParticipantRole.OWNER, turn.role)
        assertEquals(1, turn.setNumber)
        // O convidado já fez a 1: depois do dono, a próxima vez é do próprio dono na série 2.
        assertEquals(WorkoutParticipantRole.OWNER, turn.nextRole)
    }

    @Test
    fun `exercicio sem series nao tem vez nem pendencia de convidado`() {
        val duo = duo(emptyList())
        val exercise = exercise()

        assertNull(duo.turnFor(exercise))
        assertFalse(duo.hasPendingGuestSets(exercise))
    }

    @Test
    fun `sem dono ou sem convidado nao ha dupla`() {
        assertNull(DuoExecution.from(listOf(owner), emptyList()))
        assertNull(DuoExecution.from(listOf(guest), emptyList()))
        assertNull(DuoExecution.from(emptyList(), emptyList()))
    }

    @Test
    fun `rotulos vem dos participantes e o dono sem nome e Voce`() {
        val duo = duo(emptyList())

        assertEquals("Você", duo.ownerLabel)
        assertEquals("João", duo.guestLabel)
        assertEquals("João", duo.label(WorkoutParticipantRole.GUEST))
    }

    @Test
    fun `descanso do convidado e derivado do timestamp`() {
        val resting = DuoExecution.from(listOf(owner, guest.copy(restEndsAt = 10_000L)), emptyList())!!

        assertTrue(resting.isGuestResting(now = 9_999L))
        assertFalse(resting.isGuestResting(now = 10_000L))
        assertFalse(duo(emptyList()).isGuestResting(now = 0L))
    }
}
