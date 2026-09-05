package com.example.domain.ai.usecase

import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.ai.AiCoachContextProjector
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.WorkoutTemplateRevision
import com.example.domain.ai.model.ApplyWorkoutAdaptationResult
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue

/**
 * A confirmação explícita do usuário aplicando as mudanças que ele escolheu.
 *
 * É o único ponto em que uma proposta de adaptação vira dado persistido, e ele usa exatamente o
 * caminho da edição manual:
 *
 * ```
 * WorkoutAdaptationDraft -> WorkoutRepository.updateTemplateExercises
 *                        -> WorkoutDao (uma transação) -> WorkoutTemplate
 * ```
 *
 * Quatro garantias antes de qualquer escrita:
 *
 * 1. **nada selecionado, nada escrito** — nem um update vazio;
 * 2. **draft obsoleto não sobrescreve edição nova** — o template é recarregado e sua revisão
 *    determinística é comparada com a que existia quando a proposta foi montada;
 * 3. **revalidação no momento da aplicação** — cada mudança confere de novo o valor atual contra
 *    a linha recém-lida e os limites do domínio. Ter sido válida há dois minutos não significa
 *    continuar válida agora;
 * 4. **tudo ou nada** — as linhas só são escritas depois que todas as mudanças foram resolvidas,
 *    e a escrita acontece em uma transação.
 *
 * O histórico não é tocado: a adaptação altera o plano, e sessões concluídas continuam contando
 * o que realmente aconteceu.
 */
class ApplyWorkoutAdaptationUseCase(
    private val repository: WorkoutRepository
) {

    suspend operator fun invoke(
        draft: WorkoutAdaptationDraft,
        selectedChangeIds: Set<String>
    ): ApplyWorkoutAdaptationResult {
        val selected = draft.changes.filter { it.id in selectedChangeIds }
        if (selected.isEmpty()) return ApplyWorkoutAdaptationResult.NothingSelected

        val template = repository.getTemplate(draft.templateId)
            ?: return ApplyWorkoutAdaptationResult.Failure("O treino não existe mais.")
        val rows = repository.getTemplateExercisesSync(draft.templateId)

        // O treino mudou depois que a proposta foi montada: aplicar agora apagaria a edição nova.
        val currentRevision = WorkoutTemplateRevision.of(template, rows.map { it.templateExercise })
        if (currentRevision != draft.sourceRevision) return ApplyWorkoutAdaptationResult.StaleDraft

        // Trocar um exercício muda a linha inteira; ajustar carga na mesma linha ao mesmo tempo
        // deixaria ambíguo o que o usuário aceitou.
        val replacedExerciseIds = selected
            .filter { it.type == WorkoutAdaptationType.REPLACE_EXERCISE }
            .map { it.exerciseId }
            .toSet()
        selected.firstOrNull {
            it.type != WorkoutAdaptationType.REPLACE_EXERCISE && it.exerciseId in replacedExerciseIds
        }?.let { conflicting ->
            return ApplyWorkoutAdaptationResult.Failure(
                "Não dá para substituir ${conflicting.exerciseName} e ajustar o mesmo exercício " +
                    "na mesma aplicação. Escolha uma das duas."
            )
        }

        // Todo substituto é resolvido no catálogo antes de qualquer escrita: um treino pela
        // metade é pior do que um treino não alterado.
        val replacementRowIds = mutableMapOf<String, Long>()
        for (change in selected) {
            val suggested = change.suggestedValue
            if (suggested !is WorkoutAdaptationValue.Exercise) continue
            val rowId = resolveExerciseRowId(suggested.exerciseId)
                ?: return ApplyWorkoutAdaptationResult.Failure(
                    "Exercício substituto não encontrado no catálogo: ${suggested.name}"
                )
            replacementRowIds[suggested.exerciseId] = rowId
        }

        val rowsByExerciseId = rows.associateBy { AiCoachContextProjector.exerciseIdOf(it.exercise) }
        val existingRowIds = rows.mapTo(mutableSetOf()) { it.exercise.id }

        // Mudanças diferentes podem tocar o mesmo exercício: elas se acumulam sobre a mesma linha.
        val updates = linkedMapOf<Long, WorkoutTemplateExerciseEntity>()
        for (change in selected) {
            val row = rowsByExerciseId[change.exerciseId]
                ?: return ApplyWorkoutAdaptationResult.Failure(
                    "O exercício ${change.exerciseName} não está mais neste treino."
                )
            val base = updates[row.templateExercise.id] ?: row.templateExercise

            val applied = when (
                val result = apply(change, row.templateExercise, base, replacementRowIds, existingRowIds)
            ) {
                is ChangeApplication.Invalid -> return ApplyWorkoutAdaptationResult.Failure(result.reason)
                is ChangeApplication.Valid -> result.entity
            }
            updates[row.templateExercise.id] = applied
        }

        repository.updateTemplateExercises(updates.values.toList())
        return ApplyWorkoutAdaptationResult.Applied(
            templateId = draft.templateId,
            appliedChanges = selected.size
        )
    }

    private sealed interface ChangeApplication {
        data class Valid(val entity: WorkoutTemplateExerciseEntity) : ChangeApplication
        data class Invalid(val reason: String) : ChangeApplication
    }

    /**
     * Uma mudança aplicada sobre a linha do treino.
     *
     * [persisted] é a linha como está no banco agora — é contra ela que o valor atual é conferido.
     * [base] pode já carregar outra mudança aceita para o mesmo exercício.
     */
    private fun apply(
        change: WorkoutAdaptationChange,
        persisted: WorkoutTemplateExerciseEntity,
        base: WorkoutTemplateExerciseEntity,
        replacementRowIds: Map<String, Long>,
        existingRowIds: Set<Long>
    ): ChangeApplication {
        val name = change.exerciseName
        return when (val suggested = change.suggestedValue) {
            is WorkoutAdaptationValue.Load -> {
                val current = change.currentValue as? WorkoutAdaptationValue.Load
                    ?: return ChangeApplication.Invalid("Mudança de carga inconsistente em $name.")
                if (!sameWeight(current.weightKg, persisted.plannedWeight)) {
                    return ChangeApplication.Invalid("A carga de $name mudou desde a sugestão.")
                }
                val weight = suggested.weightKg
                    ?: return ChangeApplication.Invalid("Carga sugerida ausente em $name.")
                if (weight <= 0f || weight > AiCoachResponseValidator.MAX_WEIGHT_KG) {
                    return ChangeApplication.Invalid("Carga sugerida inválida em $name.")
                }
                ChangeApplication.Valid(base.copy(plannedWeight = weight))
            }

            is WorkoutAdaptationValue.Sets -> {
                val current = change.currentValue as? WorkoutAdaptationValue.Sets
                    ?: return ChangeApplication.Invalid("Mudança de séries inconsistente em $name.")
                if (current.sets != persisted.targetSets) {
                    return ChangeApplication.Invalid("As séries de $name mudaram desde a sugestão.")
                }
                if (suggested.sets < AiCoachResponseValidator.MIN_SETS ||
                    suggested.sets > AiCoachResponseValidator.MAX_SETS
                ) {
                    return ChangeApplication.Invalid("Séries sugeridas inválidas em $name.")
                }
                ChangeApplication.Valid(base.copy(targetSets = suggested.sets))
            }

            is WorkoutAdaptationValue.Reps -> {
                val current = change.currentValue as? WorkoutAdaptationValue.Reps
                    ?: return ChangeApplication.Invalid("Mudança de repetições inconsistente em $name.")
                if (current.minReps != persisted.minReps || current.maxReps != persisted.maxReps) {
                    return ChangeApplication.Invalid("As repetições de $name mudaram desde a sugestão.")
                }
                if (suggested.minReps < AiCoachResponseValidator.MIN_REPS ||
                    suggested.maxReps > AiCoachResponseValidator.MAX_REPS ||
                    suggested.maxReps < suggested.minReps
                ) {
                    return ChangeApplication.Invalid("Repetições sugeridas inválidas em $name.")
                }
                ChangeApplication.Valid(base.copy(minReps = suggested.minReps, maxReps = suggested.maxReps))
            }

            is WorkoutAdaptationValue.Rest -> {
                val current = change.currentValue as? WorkoutAdaptationValue.Rest
                    ?: return ChangeApplication.Invalid("Mudança de descanso inconsistente em $name.")
                if (current.restSeconds != persisted.restDurationSeconds) {
                    return ChangeApplication.Invalid("O descanso de $name mudou desde a sugestão.")
                }
                if (suggested.restSeconds < AiCoachResponseValidator.MIN_REST_SECONDS ||
                    suggested.restSeconds > AiCoachResponseValidator.MAX_REST_SECONDS
                ) {
                    return ChangeApplication.Invalid("Descanso sugerido inválido em $name.")
                }
                ChangeApplication.Valid(base.copy(restDurationSeconds = suggested.restSeconds))
            }

            is WorkoutAdaptationValue.Exercise -> {
                val rowId = replacementRowIds[suggested.exerciseId]
                    ?: return ChangeApplication.Invalid("Substituto não resolvido para $name.")
                if (rowId in existingRowIds) {
                    return ChangeApplication.Invalid("${suggested.name} já está neste treino.")
                }
                if (persisted.exerciseId == rowId) {
                    return ChangeApplication.Invalid("$name já é o exercício sugerido.")
                }
                // A carga planejada pertencia ao exercício antigo: mantê-la seria transferir uma
                // evidência que não existe para o novo movimento.
                ChangeApplication.Valid(base.copy(exerciseId = rowId, plannedWeight = null))
            }
        }
    }

    /** O caminho de volta do id do Coach para a linha do catálogo. Nunca por nome. */
    private suspend fun resolveExerciseRowId(exerciseId: String): Long? {
        val localRowId = AiCoachContextProjector.localRowIdOf(exerciseId)
        val exercise = if (localRowId != null) {
            repository.getExerciseByRowId(localRowId)
        } else {
            repository.getExerciseByCanonicalId(exerciseId)
        }
        return exercise?.id
    }

    private fun sameWeight(expected: Float?, persisted: Float?): Boolean = when {
        expected == null && persisted == null -> true
        expected == null || persisted == null -> false
        else -> kotlin.math.abs(expected - persisted) <= AiCoachResponseValidator.WEIGHT_TOLERANCE_KG
    }
}
