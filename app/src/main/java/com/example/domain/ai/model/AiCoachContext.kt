package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Contexto que o Spark envia ao Coach IA para uma **análise de treino**.
 *
 * Este é o contexto específico da análise, não um contexto universal: ele carrega o treino em
 * foco, o histórico dos exercícios desse treino e os PRs desses exercícios — nada além disso.
 * Nenhuma entidade Room atravessa daqui para fora e todo campo ausente permanece `null`, nunca
 * preenchido com suposição.
 *
 * Peso corporal e medidas não entram aqui: analisar carga e volume não depende deles, e enviar
 * dado corporal em toda análise seria exposição sem finalidade.
 */
@Serializable
data class AiCoachContext(
    val athlete: AiAthleteContext,
    val currentWorkout: AiWorkoutContext? = null,
    /** Histórico por exercício, já recortado por relevância e recência. */
    val exerciseHistory: List<AiExerciseHistoryContext> = emptyList(),
    val personalRecords: List<AiPersonalRecordContext> = emptyList(),
    /** Quanta evidência o app conseguiu reunir. Fato do app, não opinião do modelo. */
    val evidence: AiEvidenceContext = AiEvidenceContext()
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
            exerciseHistory.forEach { add(it.exerciseId) }
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
    /** Sessões concluídas que sustentam esta análise — origem: histórico real. */
    val completedSessionsInWindow: Int = 0
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

/**
 * A série histórica de um exercício do treino em foco.
 *
 * Só entra exercício relevante para a análise pedida, e só o suficiente para o modelo enxergar
 * a tendência — o histórico bruto nunca sai do Room.
 */
@Serializable
data class AiExerciseHistoryContext(
    val exerciseId: String,
    val name: String,
    /** Quantas execuções concluídas deste exercício estão nesta lista. */
    val sessionsAnalyzed: Int,
    /** Da execução mais recente para a mais antiga. */
    val executions: List<AiExerciseExecutionContext> = emptyList()
)

/** O que foi efetivamente executado de um exercício em uma sessão concluída. */
@Serializable
data class AiExerciseExecutionContext(
    val finishedAtEpochMs: Long? = null,
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

/**
 * Quanta evidência o app reuniu para esta análise.
 *
 * [maxDataQuality] é o teto que o modelo pode declarar: ele não pode afirmar mais evidência do
 * que recebeu. Quem calcula é [com.example.domain.ai.AiDataQualityPolicy], contando sessões —
 * nenhuma regra de progressão é decidida aqui.
 */
@Serializable
data class AiEvidenceContext(
    val sessionsAnalyzed: Int = 0,
    val exercisesWithHistory: Int = 0,
    val maxDataQuality: AiDataQualityLevel = AiDataQualityLevel.INSUFFICIENT
)
