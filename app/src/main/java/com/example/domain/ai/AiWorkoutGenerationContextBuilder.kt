package com.example.domain.ai

import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.WorkoutGenerationPreferences

/**
 * Monta o contexto de uma geração a partir das autoridades canônicas do Spark.
 *
 * Mesmo papel de [AiCoachContextBuilder], para o outro tipo de request: projeta dados que já
 * existem e não cria fonte de verdade concorrente.
 */
interface AiWorkoutGenerationContextBuilder {

    /**
     * Só os candidatos, sem o resto do contexto.
     *
     * A tela usa isto para mostrar quantos exercícios o pedido alcança e para oferecer a lista de
     * exclusão — tudo localmente, sem chamar o provider.
     */
    suspend fun candidates(preferences: WorkoutGenerationPreferences): List<AiCandidateExerciseContext>

    suspend fun build(preferences: WorkoutGenerationPreferences): AiWorkoutGenerationContext
}
