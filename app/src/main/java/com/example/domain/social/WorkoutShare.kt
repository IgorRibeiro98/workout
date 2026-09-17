package com.example.domain.social

import java.time.DayOfWeek
import kotlinx.serialization.Serializable

/**
 * Snapshot imutável e portável de uma rotina de treino compartilhada (T17.7).
 *
 * Contém apenas metadados estruturais e referências canônicas do catálogo.
 * Dados privados (cargas planejadas, anotações, histórico, máquinas, syncIds)
 * são estritamente excluídos deste modelo.
 */
@Serializable
data class SharedWorkoutSnapshot(
    val snapshotVersion: Int = 1,
    val name: String,
    val shortIdentifier: String? = null,
    val exercises: List<SharedExerciseSnapshot> = emptyList()
)

@Serializable
data class SharedExerciseSnapshot(
    val canonicalExerciseId: String,
    val sortOrder: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restDurationSeconds: Int
)

/**
 * Snapshot imutável e portável de um **programa inteiro** (T19.3): o programa e seus treinos, em
 * ordem, cada um com os mesmos exercícios portáveis do treino avulso.
 *
 * O que fica de fora é o mesmo da T17.7 — carga, nota, máquina, histórico, `localId`, `syncId` —
 * e mais o que só faz sentido para o dono: `isCurrent` e `externalId`. Quem recebe decide qual
 * programa é o atual; a oferta não decide por ele.
 */
@Serializable
data class SharedProgramSnapshot(
    val snapshotVersion: Int = 1,
    val name: String,
    val description: String? = null,
    val templates: List<SharedProgramTemplateSnapshot> = emptyList()
)

/**
 * Um treino dentro de um programa compartilhado. [orderInProgram] e [scheduledDays] são os dois
 * campos estruturais que um treino tem por pertencer a um programa — nada além disso.
 *
 * [scheduledDays] são os **0..N** dias da semana do treino (T19.8), na ordem da semana e sem
 * repetição; vazio é "sem dia fixo". Uma oferta anterior à T19.8 trazia um dia só como rótulo
 * (`dayOfWeek`), e o DTO a converte ao entrar — o domínio só conhece esta forma.
 */
@Serializable
data class SharedProgramTemplateSnapshot(
    val name: String,
    val shortIdentifier: String? = null,
    val orderInProgram: Int,
    val scheduledDays: List<DayOfWeek> = emptyList(),
    val exercises: List<SharedExerciseSnapshot> = emptyList()
)

/** O que uma oferta transporta: um treino (T17.7) ou um programa inteiro (T19.3). */
enum class WorkoutShareKind {
    WORKOUT_TEMPLATE,
    WORKOUT_PROGRAM
}

/**
 * O conteúdo portável de uma oferta, já tipado. É o que sai do aparelho ao compartilhar e o que
 * volta do servidor ao aceitar — nunca uma entidade local.
 */
sealed interface WorkoutShareContent {
    val kind: WorkoutShareKind

    /** O nome que a tela mostra: do treino, ou do programa. */
    val displayName: String

    /** Quantos treinos a oferta carrega: 1 para um treino avulso. */
    val templateCount: Int

    /** Total de exercícios — somado sobre todos os treinos, num programa. */
    val exerciseCount: Int

    data class Workout(val snapshot: SharedWorkoutSnapshot) : WorkoutShareContent {
        override val kind: WorkoutShareKind get() = WorkoutShareKind.WORKOUT_TEMPLATE
        override val displayName: String get() = snapshot.name
        override val templateCount: Int get() = 1
        override val exerciseCount: Int get() = snapshot.exercises.size
    }

    data class Program(val snapshot: SharedProgramSnapshot) : WorkoutShareContent {
        override val kind: WorkoutShareKind get() = WorkoutShareKind.WORKOUT_PROGRAM
        override val displayName: String get() = snapshot.name
        override val templateCount: Int get() = snapshot.templates.size
        override val exerciseCount: Int get() = snapshot.templates.sumOf { it.exercises.size }
    }
}

enum class WorkoutShareStatus {
    PENDING,
    ACCEPTED,
    IMPORTED,
    DECLINED,
    CANCELLED,
    EXPIRED
}

@Serializable
data class WorkoutShareOtherUser(
    val socialId: String,
    val displayName: String
)

data class WorkoutShareItem(
    val shareId: String,
    val kind: WorkoutShareKind,
    val status: WorkoutShareStatus,
    val createdAt: Long,
    val expiresAt: Long,
    /** O nome do treino — ou, numa oferta de programa, o nome do programa. */
    val templateName: String,
    val templateCount: Int,
    val exerciseCount: Int,
    val otherUser: WorkoutShareOtherUser
)

data class WorkoutShareDetail(
    val shareId: String,
    val kind: WorkoutShareKind,
    val status: WorkoutShareStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val sender: WorkoutShareOtherUser,
    val recipient: WorkoutShareOtherUser,
    /**
     * O conteúdo da oferta. Nulo quando o servidor não o enviou — inclusive quando ele conhece um
     * tipo que esta versão do app não sabe importar.
     */
    val content: WorkoutShareContent? = null
)

sealed interface WorkoutShareOutcome<out T> {
    data class Success<T>(val data: T) : WorkoutShareOutcome<T>
    data class Failure(val error: WorkoutShareError) : WorkoutShareOutcome<Nothing>
}

enum class WorkoutShareError {
    NOT_CONFIGURED,
    AUTH_REQUIRED,
    NETWORK,
    UNAVAILABLE,
    RATE_LIMITED,
    SOCIAL_NOT_ENABLED,
    FRIENDSHIP_REQUIRED,
    CANNOT_SHARE_SELF,
    BLOCKED_USER,
    SHARE_NOT_FOUND,
    INVALID_STATE,
    INVALID_SNAPSHOT,
    REJECTED
}
