package com.example.domain.ai

import com.example.data.local.ExerciseEntity
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.engine.MuscleGroup
import com.example.domain.engine.MuscleVisualResolver
import com.example.domain.exercise.import.ExerciseNormalizer

/**
 * Quais exercícios do catálogo canônico o modelo pode escolher nesta geração.
 *
 * Determinístico e puro: mesmas entradas, mesma lista, na mesma ordem. Nenhuma chamada ao
 * provider acontece antes daqui, e o modelo nunca vê o catálogo inteiro.
 *
 * ```
 * catálogo canônico -> ativos -> foco -> equipamento -> exclusões -> cota por grupo -> candidatos
 * ```
 *
 * As autoridades continuam sendo as existentes: [MuscleVisualResolver] classifica o músculo,
 * [ExerciseNormalizer] classifica o equipamento e [AiCoachContextProjector.exerciseIdOf] define a
 * identidade. Este objeto só recorta.
 */
object ExerciseCandidateBuilder {

    /** Um candidato ainda com a informação de ordenação. */
    private data class Match(
        val focusIndex: Int,
        /** 0 quando o músculo principal é do foco, 1 quando só um secundário é. */
        val rank: Int,
        val candidate: AiCandidateExerciseContext
    )

    fun build(
        catalog: List<ExerciseEntity>,
        preferences: WorkoutGenerationPreferences
    ): List<AiCandidateExerciseContext> {
        val focus = preferences.focusMuscleGroups
            .distinct()
            .take(AiModelConfig.MAX_FOCUS_MUSCLE_GROUPS)
        if (focus.isEmpty()) return emptyList()

        val allowedEquipment = if (preferences.acceptsAnyEquipment) {
            null
        } else {
            preferences.availableEquipment.flatMapTo(mutableSetOf()) { it.normalizedLabels }
        }
        val acceptsBodyweightFlag = EquipmentAvailability.BODYWEIGHT in preferences.availableEquipment

        val matches = mutableListOf<Match>()
        for (exercise in catalog) {
            if (!exercise.active) continue

            val exerciseId = AiCoachContextProjector.exerciseIdOf(exercise)
            if (exerciseId in preferences.excludedExerciseIds) continue
            if (!matchesEquipment(exercise, allowedEquipment, acceptsBodyweightFlag)) continue

            val match = matchFocus(exercise, focus) ?: continue
            matches += Match(
                focusIndex = match.first,
                rank = match.second,
                candidate = AiCandidateExerciseContext(
                    exerciseId = exerciseId,
                    name = exercise.name,
                    muscleGroup = focus[match.first].displayName,
                    equipment = exercise.equipment?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }

        // Grupo a grupo, na ordem que o usuário escolheu; dentro do grupo, principal antes de
        // secundário e depois nome, para a lista não depender da ordem do banco.
        val ordered = matches.sortedWith(
            compareBy({ it.focusIndex }, { it.rank }, { it.candidate.name.lowercase() })
        )

        val perGroup = mutableMapOf<Int, Int>()
        val selectedIds = mutableSetOf<String>()
        val selected = mutableListOf<AiCandidateExerciseContext>()
        for (match in ordered) {
            if (selected.size >= AiModelConfig.MAX_CANDIDATE_EXERCISES) break
            if (match.candidate.exerciseId in selectedIds) continue
            val used = perGroup[match.focusIndex] ?: 0
            if (used >= AiModelConfig.MAX_CANDIDATES_PER_MUSCLE_GROUP) continue
            perGroup[match.focusIndex] = used + 1
            selectedIds += match.candidate.exerciseId
            selected += match.candidate
        }
        return selected
    }

    /**
     * Onde o exercício entra no foco: `focusIndex` do grupo e se o vínculo é principal (0) ou
     * secundário (1). `null` quando o exercício não treina nada do foco.
     */
    private fun matchFocus(exercise: ExerciseEntity, focus: List<MuscleGroup>): Pair<Int, Int>? {
        val primaryIndex = focus.indexOf(MuscleVisualResolver.resolveGroup(exercise.primaryMuscle))
        if (primaryIndex >= 0) return primaryIndex to 0

        var bestIndex = -1
        exercise.secondaryMuscles
            ?.split(",")
            ?.forEach { raw ->
                val muscle = raw.trim()
                if (muscle.isEmpty()) return@forEach
                val index = focus.indexOf(MuscleVisualResolver.resolveGroup(muscle))
                if (index >= 0 && (bestIndex == -1 || index < bestIndex)) bestIndex = index
            }
        return if (bestIndex >= 0) bestIndex to 1 else null
    }

    /**
     * Se o equipamento do exercício está entre os disponíveis.
     *
     * `null` em [allowedLabels] significa academia completa: nada é filtrado. Equipamento
     * desconhecido não é tratado como disponível — supor que existe é o mesmo erro de inventar
     * dado.
     */
    private fun matchesEquipment(
        exercise: ExerciseEntity,
        allowedLabels: Set<String>?,
        acceptsBodyweightFlag: Boolean
    ): Boolean {
        if (allowedLabels == null) return true
        if (acceptsBodyweightFlag && exercise.isBodyweight) return true
        return ExerciseNormalizer.normalizeEquipment(exercise.equipment) in allowedLabels
    }
}
