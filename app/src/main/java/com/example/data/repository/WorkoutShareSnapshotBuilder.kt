package com.example.data.repository

import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateWithSchedule
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedProgramSnapshot
import com.example.domain.social.SharedProgramTemplateSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareContent

sealed interface SnapshotBuildResult {
    /** O conteúdo portável — um treino (T17.7) ou um programa inteiro (T19.3). */
    data class Success(val content: WorkoutShareContent) : SnapshotBuildResult
    data class Blocked(val reasons: List<String>) : SnapshotBuildResult
}

/**
 * Constrói snapshots portáteis e imutáveis V1 a partir do que está no Room (T17.7 / T19.3).
 *
 * Regras:
 * 1. Apenas exercícios canônicos do catálogo podem ser compartilhados.
 *    Exercícios personalizados (isUserCreated == true ou canonicalId nulo) bloqueiam o compartilhamento.
 * 2. Dados privados (cargas planejadas, anotações, números de máquina, syncIds)
 *    são completamente omitidos do snapshot gerado.
 * 3. Num programa, as duas regras valem para **cada** treino, e um treino sem exercícios bloqueia
 *    o programa inteiro: a oferta é do programa completo, ou não é.
 *
 * O bloqueio acontece **aqui**, antes de existir oferta: o servidor não conhece o catálogo e só
 * valida a forma do identificador. Quem recebe um `Blocked` recebe também o motivo, por nome.
 *
 * ## Faixas de série/repetição/descanso (H1.2)
 *
 * O editor de treino local (`TemplateDetailsScreen`) não limita `targetSets`/`minReps`/`maxReps`/
 * `restDurationSeconds` — e não deveria: Social não é autoridade de treino, e o Workout local não
 * pode ficar refém das faixas que a oferta de compartilhamento aceita. Mas
 * `WorkoutShareService` (backend) **recusa a oferta inteira** fora de `targetSets` 1..20,
 * `minReps` >= 1, `maxReps` <= 100, `minReps <= maxReps` e `restDurationSeconds` 0..600 — e, sem
 * uma checagem equivalente aqui, um treino com qualquer um desses valores fora da faixa (ex.: um
 * descanso de mais de 10 minutos digitado por engano) chegava a "Compartilhar" sem aviso nenhum e
 * só falhava depois da requisição, com o servidor recusando o conteúdo (`INVALID_SNAPSHOT`) e uma
 * mensagem genérica. As constantes abaixo espelham as mesmas faixas do serviço — se um lado mudar,
 * o outro precisa mudar junto.
 */
class WorkoutShareSnapshotBuilder {

    private companion object {
        const val MIN_TARGET_SETS = 1
        const val MAX_TARGET_SETS = 20
        const val MIN_REPS_FLOOR = 1
        const val MAX_REPS_CEILING = 100
        const val MIN_REST_SECONDS = 0
        const val MAX_REST_SECONDS = 600
    }
    fun buildSnapshot(
        template: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseWithDetails>
    ): SnapshotBuildResult {
        if (exercises.isEmpty()) {
            return SnapshotBuildResult.Blocked(listOf("O treino precisa ter pelo menos um exercício para ser compartilhado."))
        }

        customExerciseReason(exercises, owner = "O treino")?.let {
            return SnapshotBuildResult.Blocked(listOf(it))
        }
        outOfRangeReason(exercises, owner = "O treino")?.let {
            return SnapshotBuildResult.Blocked(listOf(it))
        }

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = template.name,
            shortIdentifier = template.shortIdentifier,
            exercises = exerciseSnapshots(exercises)
        )

        return SnapshotBuildResult.Success(WorkoutShareContent.Workout(snapshot))
    }

    /**
     * O programa inteiro (T19.3): nome, descrição e os treinos em ordem — cada um com os mesmos
     * exercícios portáveis do treino avulso. `isCurrent`, `externalId`, `id` e `syncId` do programa
     * ficam de fora: são do dono, não do programa.
     */
    fun buildProgramSnapshot(
        program: WorkoutProgramEntity,
        templates: List<Pair<WorkoutTemplateWithSchedule, List<TemplateExerciseWithDetails>>>
    ): SnapshotBuildResult {
        if (templates.isEmpty()) {
            return SnapshotBuildResult.Blocked(listOf("O programa precisa ter pelo menos um treino para ser compartilhado."))
        }

        val reasons = mutableListOf<String>()
        templates.forEach { (template, exercises) ->
            if (exercises.isEmpty()) {
                reasons.add("O treino \"${template.template.name}\" não tem exercícios. Adicione exercícios ou remova o treino do programa.")
            } else {
                val owner = "O treino \"${template.template.name}\""
                customExerciseReason(exercises, owner)?.let { reasons.add(it) }
                outOfRangeReason(exercises, owner)?.let { reasons.add(it) }
            }
        }
        if (reasons.isNotEmpty()) {
            return SnapshotBuildResult.Blocked(reasons)
        }

        val snapshot = SharedProgramSnapshot(
            snapshotVersion = 1,
            name = program.name,
            description = program.description?.takeIf { it.isNotBlank() },
            templates = templates
                .sortedBy { (template, _) -> template.template.orderInProgram }
                .mapIndexed { index, (template, exercises) ->
                    SharedProgramTemplateSnapshot(
                        name = template.template.name,
                        shortIdentifier = template.template.shortIdentifier,
                        // A posição viaja normalizada (0..n-1): é a ordem que importa, não o
                        // valor que o Room do remetente guardava.
                        orderInProgram = index,
                        // Os dias da semana são estruturais (T19.8): a cópia nasce com a mesma
                        // agenda, em forma canônica.
                        scheduledDays = template.scheduledDays,
                        exercises = exerciseSnapshots(exercises)
                    )
                }
        )

        return SnapshotBuildResult.Success(WorkoutShareContent.Program(snapshot))
    }

    private fun customExerciseReason(exercises: List<TemplateExerciseWithDetails>, owner: String): String? {
        val customExercises = exercises.filter {
            it.exercise.isUserCreated || it.exercise.canonicalId.isNullOrBlank()
        }
        if (customExercises.isEmpty()) return null
        val names = customExercises.map { it.exercise.name }
        return "$owner contém exercícios personalizados que não podem ser compartilhados no momento: ${names.joinToString(", ")}. " +
            "Apenas exercícios do catálogo oficial são suportados."
    }

    /**
     * Bloqueia, antes da requisição, um exercício cujas séries/repetições/descanso ficam fora do
     * que `WorkoutShareService` aceita (H1.2). Sem isto, o valor só era descoberto depois de uma
     * viagem ao servidor, como um `INVALID_SNAPSHOT` genérico e sem indicar qual exercício.
     */
    private fun outOfRangeReason(exercises: List<TemplateExerciseWithDetails>, owner: String): String? {
        val invalid = exercises.filter { detail ->
            val ex = detail.templateExercise
            ex.targetSets !in MIN_TARGET_SETS..MAX_TARGET_SETS ||
                ex.minReps < MIN_REPS_FLOOR ||
                ex.maxReps > MAX_REPS_CEILING ||
                ex.minReps > ex.maxReps ||
                ex.restDurationSeconds !in MIN_REST_SECONDS..MAX_REST_SECONDS
        }
        if (invalid.isEmpty()) return null
        val names = invalid.map { it.exercise.name }
        return "$owner tem configuração de série/repetição/descanso fora do permitido para " +
            "compartilhar: ${names.joinToString(", ")}. Séries 1–20, repetições 1–100 e descanso " +
            "até 10 minutos."
    }

    private fun exerciseSnapshots(exercises: List<TemplateExerciseWithDetails>): List<SharedExerciseSnapshot> =
        exercises.sortedBy { it.templateExercise.sortOrder }.map { detail ->
            SharedExerciseSnapshot(
                canonicalExerciseId = detail.exercise.canonicalId!!,
                sortOrder = detail.templateExercise.sortOrder,
                targetSets = detail.templateExercise.targetSets,
                minReps = detail.templateExercise.minReps,
                maxReps = detail.templateExercise.maxReps,
                restDurationSeconds = detail.templateExercise.restDurationSeconds
            )
        }
}
