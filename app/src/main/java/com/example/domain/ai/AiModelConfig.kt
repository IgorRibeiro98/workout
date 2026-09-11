package com.example.domain.ai

/**
 * Ponto único de configuração do Coach IA **no aplicativo**.
 *
 * A partir da T16.2 o que sobra aqui é o que continua sendo decisão do app: a versão do contrato
 * de conversa, os tetos de espera do transporte e os limites de contexto — porque quem monta
 * contexto continua sendo o Android, sobre o Room.
 *
 * O que **saiu** daqui e agora vive no Spark Backend: nome do modelo, temperatura, esforço de
 * raciocínio, teto de saída, timeout do provider e versão de prompt. O app não escolhe modelo, e
 * trocar de modelo não exige publicar um APK.
 */
object AiModelConfig {

    /** Versão do contrato de conversa entre o Spark e o modelo. */
    const val SCHEMA_VERSION: Int = 1

    /**
     * As versões de contrato que este build sabe interpretar.
     *
     * Existe para que uma versão desconhecida seja **recusada**, e não enviada em silêncio: se
     * [SCHEMA_VERSION] avançar sem que schema, validador e contextos acompanhem, a chamada falha
     * de forma determinística antes de tocar o provider.
     */
    val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1)

    /** Se este build sabe conversar na versão de contrato pedida. */
    fun isSupportedSchemaVersion(schemaVersion: Int): Boolean =
        schemaVersion in SUPPORTED_SCHEMA_VERSIONS

    /**
     * Teto de leitura do HTTP para uma chamada do Coach, em segundos.
     *
     * Maior que o timeout do provider no servidor (60 s — `AI_TIMEOUT_MS` em
     * `backend/src/config/env.schema.ts`) de propósito: assim quem responde primeiro é o
     * backend, com um erro tipado (`AI_PROVIDER_TIMEOUT`), em vez de o socket cair e o app ter
     * que adivinhar o que aconteceu.
     *
     * **Autoridade efetiva desde a T18.3.1.** Antes desta tarefa esta constante existia mas não
     * era lida em nenhum lugar: o transporte real usava o teto padrão e compartilhado de
     * [com.example.data.remote.spark.SparkBackendClient] (`READ_TIMEOUT_SECONDS`, 20 s) — menor
     * que o timeout do provider de então (30 s), então o app desistia (`IOException` →
     * `AiCoachErrorKind.NETWORK`, "precisa de internet") antes de o backend legitimamente
     * terminar de esperar o Gemini. `SparkBackendAiCoachGateway` agora passa este valor para
     * `SparkBackendClient.postJson(readTimeoutSeconds = ...)`, que o aplica só à chamada do
     * Coach — sync, backup, social, mídia e auth continuam no teto padrão de 20 s, porque a
     * latência deles não depende do provider de IA.
     */
    const val HTTP_READ_TIMEOUT_SECONDS: Long = 75L

    /**
     * Teto absoluto de uma chamada do Coach no app.
     *
     * É a última linha: se nem o servidor nem o socket concluírem, a corrotina encerra e a tela
     * sai do estado de carregamento. Nenhuma chamada fica pendurada, e não há repetição
     * automática — quem decide tentar de novo é o usuário.
     *
     * Maior que [HTTP_READ_TIMEOUT_SECONDS] de propósito (T18.3.1): a cadeia inteira é
     * provider do backend (60 s) < HTTP do Coach no Android (75 s) < absoluto no Android (90 s),
     * e cada camada precisa de margem para a de baixo responder primeiro com um erro tipado.
     */
    const val REQUEST_TIMEOUT_MS: Long = 90_000L

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

    /**
     * Quantos exercícios do catálogo podem ser oferecidos ao modelo em uma geração.
     *
     * O catálogo canônico tem ~400 exercícios; enviá-lo inteiro seria custo e ruído. Um treino
     * real cabe em [MAX_EXERCISES_IN_CONTEXT] exercícios, então 40 candidatos deixam o modelo
     * com cerca de três a cinco alternativas por vaga — variedade suficiente para escolher, longe
     * do catálogo inteiro.
     */
    const val MAX_CANDIDATE_EXERCISES: Int = 40

    /**
     * Teto por grupo muscular do foco.
     *
     * Sem ele, um grupo grande (peitoral tem ~50 exercícios) consumiria todas as vagas e o outro
     * grupo pedido chegaria vazio ao modelo.
     */
    const val MAX_CANDIDATES_PER_MUSCLE_GROUP: Int = 12

    /** Até quantos grupos musculares um foco aceita. Acima disso não é mais foco. */
    const val MAX_FOCUS_MUSCLE_GROUPS: Int = 4

    /**
     * De quantos candidatos o app envia carga registrada.
     *
     * Só entra exercício que o usuário realmente executou; este é o teto de quantos desses
     * cabem no contexto, escolhidos pela execução mais recente.
     */
    const val MAX_LOAD_EVIDENCE_EXERCISES: Int = 12
}
