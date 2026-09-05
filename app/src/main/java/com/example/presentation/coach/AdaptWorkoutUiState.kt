package com.example.presentation.coach

import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.WorkoutAdaptationDraft

/**
 * Estado da tela de adaptação de treino.
 *
 * [selectedChangeIds] começa vazio de propósito: cada mudança é uma decisão explícita do usuário,
 * não algo que ele precise lembrar de desmarcar. Os ids são determinísticos, então a seleção
 * sobrevive a recomposição.
 */
data class AdaptWorkoutUiState(
    val templateId: Long = -1L,
    val status: AdaptWorkoutStatus = AdaptWorkoutStatus.Idle,
    val selectedChangeIds: Set<String> = emptySet()
) {

    val isBusy: Boolean
        get() = status is AdaptWorkoutStatus.Generating || status is AdaptWorkoutStatus.Applying

    val selectedCount: Int
        get() = when (val current = status) {
            is AdaptWorkoutStatus.Draft -> current.draft.changes.count { it.id in selectedChangeIds }
            else -> 0
        }

    val canAdapt: Boolean
        get() = templateId > 0L && !isBusy

    val canApply: Boolean
        get() = status is AdaptWorkoutStatus.Draft && selectedCount > 0
}

/** O que está acontecendo com a proposta de adaptação. */
sealed interface AdaptWorkoutStatus {

    data object Idle : AdaptWorkoutStatus

    data object Generating : AdaptWorkoutStatus

    /** Proposta pronta para revisão. Nada foi alterado no treino. */
    data class Draft(val draft: WorkoutAdaptationDraft) : AdaptWorkoutStatus

    data class Applying(val draft: WorkoutAdaptationDraft) : AdaptWorkoutStatus

    /** As mudanças escolhidas foram persistidas. Só aqui o treino mudou. */
    data class Applied(val appliedChanges: Int) : AdaptWorkoutStatus

    /** O Coach olhou e não viu motivo para mudar nada. Resposta legítima, não erro. */
    data class NoChanges(
        val summary: String,
        val dataQuality: AiCoachDataQuality
    ) : AdaptWorkoutStatus

    data class Message(
        val text: String,
        val canRetry: Boolean = false,
        val isWarning: Boolean = false
    ) : AdaptWorkoutStatus
}
