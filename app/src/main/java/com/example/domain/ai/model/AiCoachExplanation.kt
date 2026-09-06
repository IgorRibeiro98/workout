package com.example.domain.ai.model

/**
 * De onde veio a pergunta do usuário.
 *
 * A origem é obrigatória em toda explicação: ela define qual autoridade resolve o alvo, qual
 * contexto mínimo é montado e qual política (local ou modelo) se aplica. Só existem os valores
 * realmente usados — não há origem declarada "para o futuro".
 */
enum class AiCoachContextOrigin {
    /** Uma recomendação ou observação da análise (T14.1). */
    WORKOUT_ANALYSIS,

    /** A proposta de treino ainda não salva (T14.2). */
    GENERATED_WORKOUT,

    /** Uma mudança proposta e ainda não aplicada (T14.3). */
    WORKOUT_ADAPTATION,

    /** Os números de progressão do Perfil do Atleta. */
    PROFILE_PROGRESS
}

/** Quem escreveu a explicação que o usuário está lendo. */
enum class AiCoachExplanationSource {
    /** Montada pelo app a partir de dados que ele já tinha. Não custa chamada nem internet. */
    LOCAL,

    /** Escrita pelo modelo sobre o contexto mínimo que o app enviou, e validada. */
    MODEL
}

/**
 * A identidade de uma explicação.
 *
 * É a chave de reutilização em memória e o que torna a explicação verificável: nunca se
 * identifica o alvo pelo texto visível.
 *
 * [sourceRevision] é a impressão do estado que originou a pergunta. Se o rascunho, o treino ou os
 * números mudarem, a revisão muda e a explicação anterior deixa de valer.
 */
data class AiCoachExplanationTarget(
    val origin: AiCoachContextOrigin,
    /** O id do objeto explicado dentro da origem (recomendação, mudança, treino, perfil). */
    val contextId: String,
    val sourceRevision: String,
    val requestType: AiCoachRequestType
)

/**
 * Uma explicação pronta para leitura.
 *
 * [evidenceItems] é sempre montado pelo app a partir do contexto enviado — nunca pelo modelo.
 * Assim "Dados considerados" mostra exatamente o que existe no Spark, e o bloco é idêntico com
 * ou sem IA.
 */
data class AiCoachExplanation(
    val requestId: String,
    val origin: AiCoachContextOrigin,
    val contextId: String,
    val title: String,
    val explanation: String,
    /** Os dados realmente usados, na ordem em que o app os reuniu. */
    val evidenceItems: List<String> = emptyList(),
    /** O que esta explicação **não** sustenta. Nunca escondido. */
    val limitations: List<String> = emptyList(),
    val source: AiCoachExplanationSource
)

/**
 * O que uma solicitação de explicação produziu.
 *
 * Toda solicitação é READ-ONLY: nenhum destes resultados escreve treino, sessão, XP, PR,
 * conquista ou missão.
 */
sealed interface AiCoachExplanationResult {
    data class Success(val explanation: AiCoachExplanation) : AiCoachExplanationResult

    /** O alvo não existe no estado atual: id inválido, rascunho descartado ou item removido. */
    data object TargetNotFound : AiCoachExplanationResult

    /** O alvo existe, mas o estado que o originou mudou. Explicar o antigo seria mentir. */
    data object StaleContext : AiCoachExplanationResult

    /** Nem o modelo respondeu nem o app conseguiu montar explicação local. */
    data class Failure(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : AiCoachExplanationResult
}

/**
 * Os números de progressão do Perfil, exatamente como as autoridades os entregaram.
 *
 * Nenhum campo é calculado aqui: nível e XP vêm de `XpTransactionRepository`, sequência e meta de
 * `ConsistencyRepository`, conquistas de `AchievementRepository`, treinos e recordes de
 * `WorkoutRepository`. A IA explica estes valores; ela não os recalcula.
 */
data class AiProgressSnapshot(
    val level: Int,
    val totalXp: Int,
    val currentLevelXp: Int,
    val xpForNextLevel: Int,
    val streakWeeks: Int,
    val weeklyCompleted: Int,
    val weeklyGoal: Int,
    val completedWorkouts: Int,
    val unlockedAchievements: Int,
    val totalAchievements: Int,
    val personalRecordsCount: Int
) {
    /** Muda sempre que qualquer número exibido muda: é o que invalida a explicação anterior. */
    val revision: String
        get() = listOf(
            level, totalXp, currentLevelXp, xpForNextLevel, streakWeeks, weeklyCompleted,
            weeklyGoal, completedWorkouts, unlockedAchievements, totalAchievements,
            personalRecordsCount
        ).joinToString(":")
}
