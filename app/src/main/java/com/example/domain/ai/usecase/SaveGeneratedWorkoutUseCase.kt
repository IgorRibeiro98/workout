package com.example.domain.ai.usecase

import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.ai.AiCoachContextProjector
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.SaveGeneratedWorkoutResult

/**
 * A confirmação explícita do usuário transformando um rascunho em treino do Spark.
 *
 * Este é o único ponto em que uma proposta da IA vira dado persistido, e ele usa exatamente o
 * caminho da criação manual:
 *
 * ```
 * GeneratedWorkoutDraft -> WorkoutRepository.addTemplate      (= "Novo Treino" na tela de programa)
 *                       -> WorkoutRepository.addTemplateExercise (= adicionar/editar no editor)
 *                       -> WorkoutDao -> WorkoutTemplate
 * ```
 *
 * Não existe repositório, DAO, tabela ou migration de IA. O gateway nunca chega até aqui: quem
 * chama é o ViewModel, depois do toque em "Salvar treino".
 *
 * Nada é atualizado: cada save **cria** um treino novo. Templates existentes e o histórico de
 * sessões permanecem intocados, inclusive quando o nome proposto coincide com o de um treino que
 * já existe — o app nunca sobrescreveu treino por nome e continua não sobrescrevendo.
 */
class SaveGeneratedWorkoutUseCase(
    private val repository: WorkoutRepository
) {

    suspend operator fun invoke(draft: GeneratedWorkoutDraft): SaveGeneratedWorkoutResult {
        if (draft.exercises.isEmpty()) {
            return SaveGeneratedWorkoutResult.Failure("O rascunho não tem exercícios.")
        }

        // Todo id precisa existir no catálogo antes de qualquer escrita: um treino pela metade é
        // pior do que um treino não criado.
        val rowIds = mutableListOf<Long>()
        for (exercise in draft.exercises) {
            val rowId = resolveExerciseRowId(exercise.exerciseId)
                ?: return SaveGeneratedWorkoutResult.Failure(
                    "Exercício não encontrado no catálogo: ${exercise.name}"
                )
            rowIds += rowId
        }

        val program = repository.getProgramForNewTemplate()
            ?: return SaveGeneratedWorkoutResult.Failure(
                "Crie um programa de treinos antes de salvar."
            )

        val existingTemplates = repository.dao.getTemplatesForProgramSync(program.id)
        val templateId = repository.addTemplate(
            programId = program.id,
            name = draft.name,
            shortId = SHORT_IDENTIFIER,
            order = existingTemplates.size
        )

        draft.exercises.forEachIndexed { index, exercise ->
            repository.addTemplateExercise(
                WorkoutTemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = rowIds[index],
                    sortOrder = exercise.sortOrder,
                    targetSets = exercise.sets,
                    minReps = exercise.minReps,
                    maxReps = exercise.maxReps,
                    restDurationSeconds = exercise.restSeconds,
                    plannedWeight = exercise.weightKg
                )
            )
        }

        return SaveGeneratedWorkoutResult.Saved(templateId = templateId, name = draft.name)
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

    companion object {
        /** Sigla dos treinos criados pelo Coach; o campo é livre e o usuário pode trocar depois. */
        const val SHORT_IDENTIFIER: String = "IA"
    }
}
