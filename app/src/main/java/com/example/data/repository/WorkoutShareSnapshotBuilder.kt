package com.example.data.repository

import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutTemplateEntity
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedWorkoutSnapshot

sealed interface SnapshotBuildResult {
    data class Success(val snapshot: SharedWorkoutSnapshot) : SnapshotBuildResult
    data class Blocked(val reasons: List<String>) : SnapshotBuildResult
}

/**
 * Constrói snapshots portáteis e imutáveis V1 a partir de um WorkoutTemplate local (T17.7).
 *
 * Regras:
 * 1. Apenas exercícios canônicos do catálogo podem ser compartilhados.
 *    Exercícios personalizados (isUserCreated == true ou canonicalId nulo) bloqueiam o compartilhamento.
 * 2. Dados privados (cargas planejadas, anotações, números de máquina, syncIds)
 *    são completamente omitidos do snapshot gerado.
 */
class WorkoutShareSnapshotBuilder {
    fun buildSnapshot(
        template: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseWithDetails>
    ): SnapshotBuildResult {
        if (exercises.isEmpty()) {
            return SnapshotBuildResult.Blocked(listOf("O treino precisa ter pelo menos um exercício para ser compartilhado."))
        }

        val customExercises = exercises.filter {
            it.exercise.isUserCreated || it.exercise.canonicalId.isNullOrBlank()
        }
        if (customExercises.isNotEmpty()) {
            val names = customExercises.map { it.exercise.name }
            return SnapshotBuildResult.Blocked(
                listOf(
                    "O treino contém exercícios personalizados que não podem ser compartilhados no momento: ${names.joinToString(", ")}. " +
                        "Apenas exercícios do catálogo oficial são suportados."
                )
            )
        }

        val snapshotExercises = exercises.sortedBy { it.templateExercise.sortOrder }.map { detail ->
            SharedExerciseSnapshot(
                canonicalExerciseId = detail.exercise.canonicalId!!,
                sortOrder = detail.templateExercise.sortOrder,
                targetSets = detail.templateExercise.targetSets,
                minReps = detail.templateExercise.minReps,
                maxReps = detail.templateExercise.maxReps,
                restDurationSeconds = detail.templateExercise.restDurationSeconds
            )
        }

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = 1,
            name = template.name,
            shortIdentifier = template.shortIdentifier,
            exercises = snapshotExercises
        )

        return SnapshotBuildResult.Success(snapshot)
    }
}
