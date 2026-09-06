package com.example.domain.ai.model

/**
 * Por que uma solicitação ao Coach não produziu conselho.
 *
 * Uma única taxonomia serve ao provider e ao caso de uso, para que a UI não precise traduzir
 * dois vocabulários de erro.
 */
enum class AiCoachErrorKind {
    /** Coach não configurado neste build, ou o Spark Backend não está disponível agora. */
    UNAVAILABLE,

    /**
     * A ação exige Conta Spark e não há conta conectada (T16.2).
     *
     * A partir da migração o Coach é uma capacidade **online autenticada**: o núcleo do Spark
     * continua completo sem conta, mas uma chamada nova ao modelo, não. Isto não é erro de
     * transporte nem falha do provider — é uma condição do produto, e por isso tem nome próprio:
     * a UI convida a entrar em vez de mostrar "falhou, tente de novo".
     */
    AUTH_REQUIRED,
    /** Sem conectividade ou falha de transporte. */
    NETWORK,
    /** Provider recusou por limite de uso. */
    RATE_LIMITED,
    /** A chamada não concluiu dentro do tempo permitido. */
    TIMEOUT,
    /** A resposta chegou, mas não passou no schema ou na validação semântica. */
    INVALID_RESPONSE,
    /** Erro do provider que não se encaixa nos anteriores. */
    PROVIDER
}

/**
 * Metadata de diagnóstico de uma chamada, decidida pelo **servidor**.
 *
 * A partir da T16.2 o Android não escolhe modelo nem versão de prompt: ele recebe qual foram. O
 * valor serve a log técnico e a diagnóstico — a UI de produção não depende dele, justamente para
 * que trocar de modelo não exija publicar um APK.
 */
data class AiCoachCallMetadata(
    /** O `requestId` do servidor, para correlacionar um erro com o log de lá. */
    val requestId: String? = null,
    val model: String = UNKNOWN_MODEL,
    val promptVersion: Int = UNKNOWN_PROMPT_VERSION
) {
    companion object {
        /** Nenhuma chamada aconteceu, ou o servidor não informou. Nunca um nome inventado. */
        const val UNKNOWN_MODEL: String = "desconhecido"
        const val UNKNOWN_PROMPT_VERSION: Int = 0

        val Unknown: AiCoachCallMetadata = AiCoachCallMetadata()
    }
}

/** Resultado bruto do provider, antes da validação semântica. */
sealed interface AiCoachGatewayResult {
    data class Success(
        val response: AiCoachResponse,
        val metadata: AiCoachCallMetadata = AiCoachCallMetadata.Unknown
    ) : AiCoachGatewayResult
    data class Error(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : AiCoachGatewayResult
}

/** Resultado do Coach para a apresentação, já validado. */
sealed interface AiCoachResult {
    data class Success(val advice: AiCoachAdvice) : AiCoachResult
    data class Failure(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : AiCoachResult
}
