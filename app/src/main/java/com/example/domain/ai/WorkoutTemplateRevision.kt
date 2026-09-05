package com.example.domain.ai

import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity

/**
 * A impressão determinística de um treino, para detectar edição concorrente.
 *
 * `WorkoutTemplateEntity` não possui `updatedAt`, `version` nem `revision` — verificado no
 * repositório. Em vez de introduzir versionamento persistido (com migration) só para isto, a
 * revisão é **derivada**: uma string estável construída a partir de todos os campos que uma
 * adaptação pode alterar.
 *
 * Regra de uso:
 *
 * ```
 * proposta montada -> revisão gravada no draft
 * aplicação        -> template recarregado -> revisão recomputada -> comparação
 * ```
 *
 * Diferente significa que o treino mudou depois da proposta: o draft está obsoleto e não pode
 * sobrescrever a edição mais nova.
 */
object WorkoutTemplateRevision {

    fun of(
        template: WorkoutTemplateEntity,
        exercises: List<WorkoutTemplateExerciseEntity>
    ): String = buildString {
        append(template.id)
        append('|')
        append(template.name)
        append('|')
        // Ordenado pelo id da linha: a revisão não pode depender da ordem em que o banco devolveu.
        exercises.sortedBy { it.id }.forEach { exercise ->
            append(exercise.id).append(':')
            append(exercise.exerciseId).append(':')
            append(exercise.sortOrder).append(':')
            append(exercise.targetSets).append(':')
            append(exercise.minReps).append('-').append(exercise.maxReps).append(':')
            append(exercise.restDurationSeconds).append(':')
            append(exercise.plannedWeight?.toString() ?: "-")
            append(';')
        }
    }
}
