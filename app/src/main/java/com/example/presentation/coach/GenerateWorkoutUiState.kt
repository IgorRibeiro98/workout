package com.example.presentation.coach

import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.model.WorkoutGoal
import com.example.domain.engine.MuscleGroup

/**
 * Estado da tela de geração de treino.
 *
 * As preferências vivem aqui — e não em `remember` da Composable — para sobreviverem a
 * recomposição e recriação de tela. [candidates] é o catálogo já filtrado localmente: ele existe
 * para o usuário saber o que o pedido alcança e escolher exclusões, e é calculado sem nenhuma
 * chamada ao provider.
 */
data class GenerateWorkoutUiState(
    val goal: WorkoutGoal = WorkoutGoal.HYPERTROPHY,
    val durationMinutes: Int = WorkoutGenerationPreferences.DEFAULT_DURATION_MINUTES,
    val focusMuscleGroups: List<MuscleGroup> = emptyList(),
    val availableEquipment: Set<EquipmentAvailability> = setOf(EquipmentAvailability.FULL_GYM),
    val excludedExerciseIds: Set<String> = emptySet(),
    val notes: String = "",
    /** Candidatos do foco/equipamento atuais, **sem** aplicar exclusões: a lista de escolha. */
    val candidates: List<AiCandidateExerciseContext> = emptyList(),
    val status: GenerateWorkoutStatus = GenerateWorkoutStatus.Idle
) {

    /** Quantos exercícios seriam realmente enviados ao modelo. */
    val candidateCount: Int
        get() = candidates.count { it.exerciseId !in excludedExerciseIds }

    val isBusy: Boolean
        get() = status is GenerateWorkoutStatus.Generating || status is GenerateWorkoutStatus.Saving

    /** O botão só libera com foco escolhido, candidatos disponíveis e nenhuma chamada em curso. */
    val canGenerate: Boolean
        get() = focusMuscleGroups.isNotEmpty() && candidateCount > 0 && !isBusy

    fun preferences(): WorkoutGenerationPreferences = WorkoutGenerationPreferences(
        goal = goal,
        durationMinutes = durationMinutes,
        focusMuscleGroups = focusMuscleGroups,
        availableEquipment = availableEquipment,
        excludedExerciseIds = excludedExerciseIds,
        notes = notes.trim().takeIf { it.isNotEmpty() }
    )
}

/** O que está acontecendo com a proposta. */
sealed interface GenerateWorkoutStatus {

    data object Idle : GenerateWorkoutStatus

    data object Generating : GenerateWorkoutStatus

    /** Proposta pronta para revisão. Nada foi persistido. */
    data class Draft(val draft: GeneratedWorkoutDraft) : GenerateWorkoutStatus

    data class Saving(val draft: GeneratedWorkoutDraft) : GenerateWorkoutStatus

    /** O usuário confirmou e o treino existe. [templateId] abre o editor canônico. */
    data class Saved(val templateId: Long, val name: String) : GenerateWorkoutStatus

    /**
     * A geração exige Conta Spark e não há conta conectada (T16.2).
     *
     * Estado próprio, e não um `Message`: aqui não houve falha — o que a tela precisa oferecer é
     * um convite para entrar, e criar treino à mão continua funcionando sem conta.
     */
    data object AuthRequired : GenerateWorkoutStatus

    /**
     * Um recado para o usuário.
     *
     * [isWarning] separa "o Coach não está disponível" de "a chamada falhou"; [canRetry] diz se
     * insistir agora faz sentido.
     */
    data class Message(
        val text: String,
        val canRetry: Boolean = false,
        val isWarning: Boolean = false
    ) : GenerateWorkoutStatus
}
