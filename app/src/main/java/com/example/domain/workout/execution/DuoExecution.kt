package com.example.domain.workout.execution

import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutGuestSetLogEntity
import com.example.data.local.WorkoutParticipantRole
import com.example.data.local.WorkoutSessionParticipantEntity

/**
 * A dimensão de participante de uma sessão `DUO_LOCAL` (T19.4): quem participa e o que o
 * convidado já fez.
 *
 * É `null` numa sessão solo, e é assim — e não por um `if (mode == SOLO)` espalhado — que o modo
 * solo continua sendo o caminho simples: toda regra abaixo só existe quando há um convidado.
 *
 * As séries do dono continuam sendo as `set_logs` do exercício; as do convidado são o espelho em
 * `workout_guest_set_logs`. Este modelo não persiste nada: ele lê o estado durável e responde a
 * duas perguntas — "este exercício ainda tem série pendente?" e "de quem é a vez?".
 */
data class DuoExecution(
    val owner: WorkoutSessionParticipantEntity,
    val guest: WorkoutSessionParticipantEntity,
    private val guestSetsByExercise: Map<Long, List<WorkoutGuestSetLogEntity>>
) {
    companion object {
        /** O rótulo do dono quando ele não tem nome local — o Spark não guarda nome de perfil. */
        const val OWNER_DEFAULT_LABEL = "Você"

        fun from(
            participants: List<WorkoutSessionParticipantEntity>,
            guestSets: List<WorkoutGuestSetLogEntity>
        ): DuoExecution? {
            val owner = participants.firstOrNull { it.role == WorkoutParticipantRole.OWNER.name } ?: return null
            val guest = participants
                .filter { it.role == WorkoutParticipantRole.GUEST.name }
                .minByOrNull { it.position } ?: return null
            return DuoExecution(
                owner = owner,
                guest = guest,
                guestSetsByExercise = guestSets
                    .filter { it.participantId == guest.id }
                    .groupBy { it.exerciseSessionId }
                    .mapValues { (_, sets) -> sets.sortedWith(compareBy({ it.setNumber }, { it.id })) }
            )
        }
    }

    val ownerLabel: String get() = owner.displayName?.takeIf { it.isNotBlank() } ?: OWNER_DEFAULT_LABEL
    val guestLabel: String get() = guest.displayName?.takeIf { it.isNotBlank() } ?: "Convidado"

    fun label(role: WorkoutParticipantRole): String = when (role) {
        WorkoutParticipantRole.OWNER -> ownerLabel
        WorkoutParticipantRole.GUEST -> guestLabel
    }

    fun guestSetsFor(exerciseSessionId: Long): List<WorkoutGuestSetLogEntity> =
        guestSetsByExercise[exerciseSessionId].orEmpty()

    /** O convidado ainda tem série pendente neste exercício? Sem linha para uma série do dono conta como pendente. */
    fun hasPendingGuestSets(exercise: ExerciseSessionWithSets): Boolean =
        DuoTurnResolver.hasPendingGuestSets(exercise.sets, guestSetsFor(exercise.exerciseSession.id))

    /** De quem é a vez neste exercício — ou `null` quando os dois já concluíram tudo dele. */
    fun turnFor(exercise: ExerciseSessionWithSets): DuoTurn? =
        DuoTurnResolver.resolve(this, exercise)

    /** O descanso do convidado está em andamento em [now]? Derivado do timestamp, nunca de contagem. */
    fun isGuestResting(now: Long): Boolean = (guest.restEndsAt ?: 0L) > now
}

/**
 * A vez de um participante numa série de um exercício.
 *
 * É o estado canônico de "participante atual" da tela (§6.6 da T19.4), e é **reconstruível**: sai
 * inteiro das séries persistidas dos dois participantes, então uma morte de processo entre a
 * série do dono e a do convidado recomeça exatamente na vez do convidado.
 */
data class DuoTurn(
    val role: WorkoutParticipantRole,
    val participant: WorkoutSessionParticipantEntity,
    /** A série da vez, 1-based — o mesmo `setNumber` para os dois participantes. */
    val setNumber: Int,
    /** A posição da série na lista ordenada do exercício (0-based), para "Série N de M". */
    val setIndex: Int,
    /** A `set_logs` do dono quando é a vez dele. */
    val ownerSet: SetLogEntity?,
    /** A linha do convidado quando é a vez dele. */
    val guestSet: WorkoutGuestSetLogEntity?,
    /** Quem vem depois desta série, se alguém: o outro participante na mesma série, ou o dono na próxima. */
    val nextRole: WorkoutParticipantRole?,
    /** É a última série pendente **do exercício**, considerando os dois participantes. */
    val isLastTurnOfExercise: Boolean
)

/**
 * A regra de alternância da dupla, pura e sem estado (T19.4 §6.2 / §6.5):
 *
 * ```text
 * Owner série N  →  Guest série N  →  Owner série N+1  →  Guest série N+1  →  ...
 * ```
 *
 * A série da vez é a **menor** `setNumber` em que algum dos dois ainda não concluiu; dentro dela,
 * o dono vem antes do convidado. Uma série do dono sem espelho do convidado conta como pendente
 * para o convidado — a ausência de linha nunca faz uma série "pular".
 *
 * Só o dono tem `set_logs`; a lista do convidado vem de `workout_guest_set_logs`. Nenhuma das duas
 * é reordenada aqui: quem chama passa as séries na ordem de domínio (`sortedSets`).
 */
object DuoTurnResolver {

    fun hasPendingGuestSets(ownerSets: List<SetLogEntity>, guestSets: List<WorkoutGuestSetLogEntity>): Boolean {
        if (ownerSets.isEmpty()) return false
        val guestByNumber = guestSets.associateBy { it.setNumber }
        return ownerSets.any { ownerSet -> guestByNumber[ownerSet.setNumber]?.completed != true }
    }

    fun resolve(duo: DuoExecution, exercise: ExerciseSessionWithSets): DuoTurn? {
        val ownerSets = exercise.sets
        if (ownerSets.isEmpty()) return null
        val guestByNumber = duo.guestSetsFor(exercise.exerciseSession.id).associateBy { it.setNumber }

        val pending = ownerSets.withIndex().map { (index, ownerSet) ->
            val guestSet = guestByNumber[ownerSet.setNumber]
            Triple(index, ownerSet, guestSet)
        }.filter { (_, ownerSet, guestSet) -> !ownerSet.completed || guestSet?.completed != true }

        val (index, ownerSet, guestSet) = pending.firstOrNull() ?: return null
        val ownerPendingHere = !ownerSet.completed
        val guestPendingHere = guestSet?.completed != true
        val isLastPendingSet = pending.size == 1

        return if (ownerPendingHere) {
            DuoTurn(
                role = WorkoutParticipantRole.OWNER,
                participant = duo.owner,
                setNumber = ownerSet.setNumber,
                setIndex = index,
                ownerSet = ownerSet,
                guestSet = null,
                nextRole = when {
                    guestPendingHere -> WorkoutParticipantRole.GUEST
                    !isLastPendingSet -> WorkoutParticipantRole.OWNER
                    else -> null
                },
                isLastTurnOfExercise = isLastPendingSet && !guestPendingHere
            )
        } else {
            DuoTurn(
                role = WorkoutParticipantRole.GUEST,
                participant = duo.guest,
                setNumber = ownerSet.setNumber,
                setIndex = index,
                ownerSet = null,
                // Sem espelho persistido (não acontece pelo caminho normal: as linhas nascem com a
                // sessão e acompanham addSet/removeSet), a vez do convidado ainda existe. A linha
                // transitória tem `id = 0`, e o motor a insere ao gravar.
                guestSet = guestSet ?: WorkoutGuestSetLogEntity(
                    participantId = duo.guest.id,
                    exerciseSessionId = exercise.exerciseSession.id,
                    setNumber = ownerSet.setNumber,
                    weight = ownerSet.weight,
                    repetitions = ownerSet.repetitions
                ),
                nextRole = if (!isLastPendingSet) WorkoutParticipantRole.OWNER else null,
                isLastTurnOfExercise = isLastPendingSet
            )
        }
    }
}
