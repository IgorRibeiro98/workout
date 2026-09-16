package com.example.domain.multiplayer

import java.security.MessageDigest

/**
 * Um exercício da sessão **deste** aparelho, reduzido ao que a sala precisa saber (T19.5 §6.4).
 *
 * Posição, referência canônica, quantas séries e quais já foram concluídas — e nada mais. O peso e
 * a repetição de cada série ficam no Room; esta projeção não tem campo para eles.
 */
data class LocalExerciseProgress(
    /** Posição na execução, 1-based (`executionOrder`). */
    val position: Int,
    val canonicalExerciseId: String?,
    /** Identidade local da linha — entra só no `eventId`, nunca no payload. */
    val exerciseSessionId: Long,
    val setCount: Int,
    /** `setNumber` → `finishedAt` (relógio do aparelho, informativo) das séries concluídas. */
    val completedSets: Map<Int, Long?>
)

/**
 * Os eventos que este aparelho publica, **derivados do estado persistido** — nunca de um toque.
 *
 * ## Por que derivar, e não capturar
 *
 * A série é gravada no Room pelo `WorkoutEngine`; o evento é o Room dizendo "esta série está
 * concluída". Assim o evento não depende de a tela estar aberta, de o toque ter sido no lugar
 * certo, nem de o processo ter sobrevivido: uma morte de processo entre gravar a série e publicar
 * o evento é resolvida na reabertura, porque a série continua gravada e o evento é derivado de
 * novo — com o **mesmo** `eventId`.
 *
 * ## `eventId` determinístico
 *
 * `sha256(roomId | fato)` — o mesmo fato produz o mesmo id em qualquer reenvio, e o servidor
 * responde com a sequence que ele já tinha. É isso que torna o reenvio integral depois de uma
 * reconexão uma operação segura, e não uma fonte de duplicatas. O `exerciseSessionId` local entra
 * no hash (é o que distingue duas séries "1" de dois exercícios) e não sai dele.
 */
object LocalMultiplayerEvents {

    fun eventId(roomId: String, fact: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$roomId|$fact".toByteArray(Charsets.UTF_8))
        return "e-" + digest.joinToString("") { "%02x".format(it) }.substring(0, 40)
    }

    fun workoutStarted(roomId: String, sessionId: Long, exerciseCount: Int): OutgoingMultiplayerEvent =
        OutgoingMultiplayerEvent(
            eventId = eventId(roomId, "started:$sessionId"),
            type = MultiplayerEventType.WORKOUT_STARTED,
            payload = MultiplayerEventPayload.WorkoutStarted(exerciseCount = exerciseCount.coerceIn(1, 30))
        )

    fun setCompleted(
        roomId: String,
        exercise: LocalExerciseProgress,
        setNumber: Int,
        finishedAt: Long?
    ): OutgoingMultiplayerEvent = OutgoingMultiplayerEvent(
        eventId = eventId(roomId, "set:${exercise.exerciseSessionId}:$setNumber"),
        type = MultiplayerEventType.SET_COMPLETED,
        payload = MultiplayerEventPayload.SetCompleted(
            canonicalExerciseId = exercise.canonicalExerciseId,
            exercisePosition = exercise.position,
            setNumber = setNumber,
            setCount = maxOf(exercise.setCount, setNumber),
            completedAt = finishedAt ?: 0L
        )
    )

    fun memberFinished(roomId: String, sessionId: Long): OutgoingMultiplayerEvent =
        OutgoingMultiplayerEvent(
            eventId = eventId(roomId, "finished:$sessionId"),
            type = MultiplayerEventType.MEMBER_FINISHED,
            payload = MultiplayerEventPayload.Empty
        )

    /**
     * Tudo o que a sessão atual já diz sobre si: o início e cada série concluída, na ordem em que
     * foram concluídas (empate por posição e série). É a lista inteira, sempre — quem já foi
     * publicado é filtrado por quem chama, e reenviar o resto é dedupe no servidor.
     */
    fun derive(roomId: String, sessionId: Long, exercises: List<LocalExerciseProgress>): List<OutgoingMultiplayerEvent> {
        val events = mutableListOf(workoutStarted(roomId, sessionId, exercises.size))
        exercises
            .flatMap { exercise -> exercise.completedSets.map { (setNumber, finishedAt) -> Triple(exercise, setNumber, finishedAt) } }
            .sortedWith(compareBy({ it.third ?: Long.MAX_VALUE }, { it.first.position }, { it.second }))
            .forEach { (exercise, setNumber, finishedAt) ->
                events.add(setCompleted(roomId, exercise, setNumber, finishedAt))
            }
        return events
    }
}
