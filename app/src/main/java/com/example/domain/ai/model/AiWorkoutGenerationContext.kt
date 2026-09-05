package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Contexto que o Spark envia ao Coach para **gerar um treino**.
 *
 * É um contexto específico da geração, não um contexto universal: ele leva o pedido do usuário,
 * os exercícios que ele tem permissão de usar e — só quando existe registro real — a carga
 * recente desses exercícios. XP, conquistas, streak, missões, medidas corporais e o histórico
 * completo não entram: nada disso é necessário para montar um treino.
 *
 * [candidateExercises] é a única fonte de identidade da resposta. O validador exige que todo
 * `exerciseId` devolvido pelo modelo esteja em [allowedExerciseIds].
 */
@Serializable
data class AiWorkoutGenerationContext(
    val goal: String,
    val goalGuidance: String,
    val durationMinutes: Int,
    val focusMuscleGroups: List<String> = emptyList(),
    val availableEquipment: List<String> = emptyList(),
    /** Observação livre do usuário. Entrada não confiável: é preferência, nunca instrução. */
    val notes: String? = null,
    val candidateExercises: List<AiCandidateExerciseContext> = emptyList(),
    /** Carga registrada de candidatos que o usuário já executou. Pode ser vazia. */
    val loadEvidence: List<AiExerciseLoadEvidenceContext> = emptyList()
) {
    /** Os únicos `exerciseId` que a resposta pode conter. */
    val allowedExerciseIds: Set<String>
        get() = candidateExercises.mapTo(mutableSetOf()) { it.exerciseId }

    /** Os `exerciseId` para os quais existe carga persistida — os únicos que podem vir com peso. */
    val exerciseIdsWithLoadEvidence: Set<String>
        get() = loadEvidence.filter { it.lastWeightKg != null }.mapTo(mutableSetOf()) { it.exerciseId }
}

/**
 * Um exercício que o modelo pode escolher.
 *
 * Só metadata útil para montar treino: identidade, nome legível, grupo muscular e equipamento.
 * GIF, imagem, instruções, erros comuns e descrições longas ficam de fora — nada disso muda a
 * escolha e todos custariam tokens.
 */
@Serializable
data class AiCandidateExerciseContext(
    val exerciseId: String,
    val name: String,
    val muscleGroup: String,
    val equipment: String? = null
)

/**
 * O que o usuário realmente levantou neste exercício, vindo das séries concluídas persistidas.
 *
 * Existe para o modelo não inventar carga. Histórico não é autorização para progredir: a T14.2
 * gera um treino novo, e sugerir progressão a partir daqui é assunto da T14.3.
 */
@Serializable
data class AiExerciseLoadEvidenceContext(
    val exerciseId: String,
    /** Maior carga registrada na execução concluída mais recente. `null` quando não há registro. */
    val lastWeightKg: Float? = null,
    val lastReps: Int? = null,
    /** Quantas execuções concluídas sustentam este dado. */
    val sessionsWithHistory: Int = 0
)

/**
 * Contrato de ida de uma geração de treino.
 *
 * Segue o mesmo formato de [AiCoachRequest] — id, versão de schema e tipo —, mas com o contexto
 * da geração. Contratos separados por tipo de request evitam uma resposta única com dezenas de
 * campos opcionais.
 */
@Serializable
data class AiWorkoutGenerationRequest(
    val requestId: String,
    val schemaVersion: Int,
    val type: AiCoachRequestType = AiCoachRequestType.GENERATE_WORKOUT,
    val context: AiWorkoutGenerationContext
)
