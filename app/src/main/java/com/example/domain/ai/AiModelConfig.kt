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

    /** A resposta desta fase é curta por contrato; o teto evita custo acidental. */
    const val MAX_OUTPUT_TOKENS: Int = 1024

    /** Nenhuma chamada pode ficar em loading indefinidamente. */
    const val REQUEST_TIMEOUT_MS: Long = 30_000L

    /**
     * Quantas sessões concluídas recentes entram no contexto.
     *
     * Cinco cobrem o ciclo semanal típico do Spark sem transformar o prompt no banco inteiro.
     */
    const val RECENT_SESSIONS_LIMIT: Int = 5

    /** Teto de PRs enviados, sempre restrito aos exercícios que já estão no contexto. */
    const val PERSONAL_RECORDS_LIMIT: Int = 10
}
