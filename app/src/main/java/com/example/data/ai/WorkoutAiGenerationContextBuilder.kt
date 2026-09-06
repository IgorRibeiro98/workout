package com.example.data.ai

import com.example.data.local.WorkoutDao
import com.example.domain.ai.AiCoachContextProjector
import com.example.domain.ai.AiUserText
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.AiWorkoutGenerationContextBuilder
import com.example.domain.ai.ExerciseCandidateBuilder
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.WorkoutGenerationPreferences

/**
 * Lê o catálogo canônico e o histórico persistido e entrega o contexto da geração.
 *
 * Todo o IO fica aqui; o recorte determinístico fica em [ExerciseCandidateBuilder] e em
 * [AiCoachContextProjector]. Este builder só lê — nenhuma escrita em Room ou DataStore.
 *
 * O catálogo vem de `getAllExercisesList()`, que já devolve apenas exercícios ativos: a mesma
 * lista que a tela de exercícios e o editor de treino enxergam.
 */
class WorkoutAiGenerationContextBuilder(
    private val workoutDao: WorkoutDao
) : AiWorkoutGenerationContextBuilder {

    override suspend fun candidates(
        preferences: WorkoutGenerationPreferences
    ): List<AiCandidateExerciseContext> =
        ExerciseCandidateBuilder.build(workoutDao.getAllExercisesList(), preferences)

    override suspend fun build(preferences: WorkoutGenerationPreferences): AiWorkoutGenerationContext {
        val catalog = workoutDao.getAllExercisesList()
        val candidates = ExerciseCandidateBuilder.build(catalog, preferences)

        // Sem candidato não há o que gerar: o caso de uso corta antes de chamar o provider, então
        // nem o histórico é lido.
        val loadEvidence = if (candidates.isEmpty()) {
            emptyList()
        } else {
            AiCoachContextProjector.projectLoadEvidence(
                exercisesById = catalog.associateBy { it.id },
                candidateExerciseIds = candidates.mapTo(mutableSetOf()) { it.exerciseId },
                completedSessions = workoutDao.getAllCompletedSessionsWithDetails()
                    .take(AiModelConfig.HISTORY_SCAN_SESSIONS)
            )
        }

        return AiWorkoutGenerationContext(
            goal = preferences.goal.name,
            goalGuidance = preferences.goal.guidance,
            durationMinutes = preferences.durationMinutes,
            focusMuscleGroups = preferences.focusMuscleGroups.map { it.displayName },
            availableEquipment = if (preferences.acceptsAnyEquipment) {
                emptyList()
            } else {
                preferences.availableEquipment.map { it.label }.sorted()
            },
            // O único texto livre do usuário em todo o Coach. Atravessa a fronteira como dado:
            // sem caracteres de controle, com tamanho limitado — e o prompt diz explicitamente
            // que texto do usuário não é instrução.
            notes = AiUserText.sanitize(
                raw = preferences.notes,
                maxLength = WorkoutGenerationPreferences.MAX_NOTES_LENGTH
            ),
            candidateExercises = candidates,
            loadEvidence = loadEvidence
        )
    }
}
