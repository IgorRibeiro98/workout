package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Como uma sessão é executada (T19.4 / T19.5).
 *
 * `SOLO` é o padrão e o caminho canônico: toda sessão anterior à T19.4 é `SOLO` por `DEFAULT` na
 * migração, e nada da execução solo lê as tabelas de participante. `DUO_LOCAL` é duas pessoas, um
 * aparelho, uma sessão — o convidado é local, sem conta, e a sessão continua sendo do dono.
 *
 * `DUO_REMOTE` (T19.5) é duas pessoas, **dois** aparelhos: a execução deste aparelho é idêntica ao
 * `SOLO` — mesmas `set_logs`, mesmo PR, mesmo XP, nenhuma tabela de participante — e o que se soma
 * é um vínculo com uma sala no Spark Backend (`workout_session_multiplayer_links`), por onde os dois
 * aparelhos se enxergam. A sessão continua sendo deste aparelho, e o servidor nunca a escreve.
 *
 * Não existe `TRIO`.
 */
enum class WorkoutExecutionMode { SOLO, DUO_LOCAL, DUO_REMOTE }

/** Quem é o participante dentro da execução: o dono do aparelho, ou um convidado local. */
enum class WorkoutParticipantRole { OWNER, GUEST }

/**
 * Um participante de uma sessão em modo dupla (T19.4).
 *
 * O `id` é a identidade **técnica** do participante dentro da execução — é por ele que as séries
 * do convidado e o descanso dele são endereçados. `displayName` é rótulo, nunca identidade: dois
 * convidados chamados "João" em sessões diferentes são pessoas diferentes, e um nome vazio não
 * quebra nada.
 *
 * `restEndsAt` é o descanso do **convidado**, por timestamp (`null` = sem descanso em andamento).
 * O descanso do dono continua sendo o temporizador do aparelho (`SettingsManager.restTimerDeadline`
 * + `WorkoutEngine.restTimerTarget`), com a notificação e a restauração que já existiam; a linha
 * `OWNER` guarda `null` aqui por definição. Um relógio só por participante, e nenhum deles vive em
 * estado de tela.
 *
 * Sessões `SOLO` não têm linha nenhuma nesta tabela.
 */
@Entity(
    tableName = "workout_session_participants",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "position"], unique = true)
    ]
)
data class WorkoutSessionParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    /** Nulo para o dono: a tela mostra "Você". O convidado sempre recebe um nome. */
    val displayName: String? = null,
    /** Ordem determinística da alternância: o dono é 0, o convidado 1. */
    val position: Int,
    val restEndsAt: Long? = null
)

/**
 * A série de um convidado, espelho operacional de uma `set_logs` do dono (T19.4).
 *
 * Existe para que a alternância e a recuperação da execução sobrevivam à morte do processo: quem
 * já fez a série N, com que carga, e quando. **Não é histórico do convidado** — nada em PR, XP,
 * estatística, calendário, sync ou backup lê esta tabela, e é isso que garante que o dono não
 * recebe recompensa dobrada e que nenhum dado do convidado sai do aparelho.
 *
 * A identidade de uma série do convidado é `(participantId, exerciseSessionId, setNumber)`: o
 * mesmo `setNumber` da série do dono no mesmo exercício. As linhas nascem junto com as `set_logs`
 * ao iniciar a sessão e acompanham `addSet`/`removeSet`.
 */
@Entity(
    tableName = "workout_guest_set_logs",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ExerciseSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseSessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("participantId"),
        Index("exerciseSessionId"),
        Index(value = ["participantId", "exerciseSessionId", "setNumber"], unique = true)
    ]
)
data class WorkoutGuestSetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: Long,
    val exerciseSessionId: Long,
    val setNumber: Int,
    val weight: Float = 0f,
    val repetitions: Int = 0,
    val completed: Boolean = false,
    val finishedAt: Long? = null,
    val rir: Int? = null,
    val durationSeconds: Int? = null
) {
    /**
     * A série do convidado com a forma de uma `SetLogEntity`, para a tela que edita uma série.
     *
     * É projeção, não linha: o `id` é o **desta** tabela e o valor nunca deve ser gravado em
     * `set_logs`. Quem recebe a projeção de volta da tela copia os valores para a linha do
     * convidado (`WorkoutGuestSetLogEntity.withValuesFrom`).
     */
    fun asSetLogProjection(): SetLogEntity = SetLogEntity(
        id = id,
        exerciseSessionId = exerciseSessionId,
        setNumber = setNumber,
        weight = weight,
        repetitions = repetitions,
        completed = completed,
        finishedAt = finishedAt,
        rir = rir,
        durationSeconds = durationSeconds
    )

    fun withValuesFrom(edited: SetLogEntity): WorkoutGuestSetLogEntity = copy(
        weight = edited.weight,
        repetitions = edited.repetitions,
        rir = edited.rir,
        durationSeconds = edited.durationSeconds
    )
}
