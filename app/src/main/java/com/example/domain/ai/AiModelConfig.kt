package com.example.domain.ai

/** Esforço de raciocínio pedido ao modelo, sem vazar o enum do SDK para o domínio. */
enum class AiThinkingLevel {
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Ponto único de configuração do Coach IA.
 *
 * Trocar de modelo, de esforço de raciocínio ou de timeout acontece aqui e em nenhum outro
 * arquivo — o domínio, o ViewModel e a UI não conhecem nome de modelo.
 */
object AiModelConfig {

    /** Modelo do Coach (Firebase AI Logic / Gemini Developer API). */
    const val MODEL_NAME: String = "gemini-3.6-flash"

    /** Versão do contrato de conversa entre o Spark e o modelo. */
    const val SCHEMA_VERSION: Int = 1

    /** Configuração conservadora para a primeira versão. */
    val THINKING_LEVEL: AiThinkingLevel = AiThinkingLevel.MEDIUM

    /** Análise pede consistência, não criatividade. */
    const val TEMPERATURE: Float = 0.2f

    /** A análise tem resumo, sinais, pontos de atenção e sugestões; o teto evita custo acidental. */
    const val MAX_OUTPUT_TOKENS: Int = 2048

    /** Nenhuma chamada pode ficar em loading indefinidamente. */
    const val REQUEST_TIMEOUT_MS: Long = 30_000L

    /**
     * Quantas execuções concluídas de **cada** exercício entram no contexto.
     *
     * Seis cobrem mais de um ciclo semanal típico do Spark: o suficiente para o modelo enxergar
     * uma tendência de carga/reps sem transformar o prompt no banco inteiro.
     */
    const val HISTORY_PER_EXERCISE_LIMIT: Int = 6

    /**
     * Até quantas sessões concluídas o app percorre para encontrar essas execuções.
     *
     * Teto de custo da montagem do contexto: quem treina 4x por semana tem ~7 semanas de
     * histórico dentro desta janela.
     */
    const val HISTORY_SCAN_SESSIONS: Int = 30

    /** Teto de exercícios analisados por vez; um treino real não passa disso. */
    const val MAX_EXERCISES_IN_CONTEXT: Int = 12

    /** Teto de PRs enviados, sempre restrito aos exercícios que já estão no contexto. */
    const val PERSONAL_RECORDS_LIMIT: Int = 10
}
