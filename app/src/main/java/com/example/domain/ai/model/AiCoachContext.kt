package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Contexto que o Spark envia ao Coach IA.
 *
 * Este é um DTO próprio da fronteira de IA: nenhuma entidade Room atravessa daqui para fora.
 * Ele é deliberadamente pequeno — só o necessário para uma análise do treino — e todo campo
 * ausente permanece `null`, nunca preenchido com suposição.
 */
@Serializable
data class AiCoachContext(
    val athlete: AiAthleteContext,
    val currentWorkout: AiWorkoutContext? = null,
    val recentSessions: List<AiSessionContext> = emptyList(),
    val personalRecords: List<AiPersonalRecordContext> = emptyList()
) {
    /**
     * Todos os `exerciseId` que o modelo tem permissão de citar.
     *
     * A validação usa exatamente este conjunto: recomendação sobre exercício fora daqui é
     * invenção e é rejeitada.
     */
    val knownExerciseIds: Set<String>
        get() = buildSet {
            currentWorkout?.exercises?.forEach { add(it.exerciseId) }
            recentSessions.forEach { session -> session.exercises.forEach { add(it.exerciseId) } }
            personalRecords.forEach { add(it.exerciseId) }
        }
}

/**
 * O que o Spark realmente sabe sobre o atleta hoje.
 *
 * Objetivo, nível de experiência, RPE, dor e fadiga ainda não existem como dado do app; por isso
 * não aparecem aqui. Quando passarem a existir, entram como campos anuláveis explícitos.
 */
@Serializable
data class AiAthleteContext(
    /** Meta semanal de treinos configurada pelo usuário. */
    val weeklyGoal: Int? = null,
    /** Treinos concluídos na janela recente projetada — origem: histórico real. */
    val completedSessionsInWindow: Int = 0,
    /** Última medição corporal registrada, quando existir. */
    val bodyWeightKg: Float? = null
)

/** O treino planejado que está em foco na análise. */
@Serializable
data class AiWorkoutContext(
    val templateName: String? = null,
    val exercises: List<AiPlannedExerciseContext> = emptyList()
)

/** Um exercício planejado. A identidade é [exerciseId]; [name] existe só para leitura do modelo. */
@Serializable
data class AiPlannedExerciseContext(
    val exerciseId: String,
    val name: String,
    val targetSets: Int? = null,
    val minReps: Int? = null,
    val maxReps: Int? = null,
    val plannedWeightKg: Float? = null,
    val restSeconds: Int? = null
)

/** Uma sessão concluída, já resumida. O histórico bruto nunca sai do Room. */
@Serializable
data class AiSessionContext(
    val finishedAtEpochMs: Long? = null,
    val durationMinutes: Int? = null,
    val exercises: List<AiExecutedExerciseContext> = emptyList()
)

/** O que foi efetivamente executado de um exercício em uma sessão. */
@Serializable
data class AiExecutedExerciseContext(
    val exerciseId: String,
    val name: String,
    val completedSets: Int,
    val maxWeightKg: Float? = null,
    val totalReps: Int? = null
)

/** Um recorde pessoal já reconhecido pelo domínio. A IA não cria PR. */
@Serializable
data class AiPersonalRecordContext(
    val exerciseId: String,
    val name: String,
    val type: String,
    val value: Float,
    val achievedAtEpochMs: Long? = null
)
