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
 */
class WorkoutShareSnapshotBuilder {
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
                customExerciseReason(exercises, owner = "O treino \"${template.template.name}\"")?.let { reasons.add(it) }
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
