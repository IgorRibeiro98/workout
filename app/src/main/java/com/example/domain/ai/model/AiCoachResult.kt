package com.example.domain.ai.model

/**
 * Por que uma solicitação ao Coach não produziu conselho.
 *
 * Uma única taxonomia serve ao provider e ao caso de uso, para que a UI não precise traduzir
 * dois vocabulários de erro.
 */
enum class AiCoachErrorKind {
    /** Firebase/Coach não configurado neste build ou desabilitado no projeto. */
    UNAVAILABLE,
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

/** Resultado bruto do provider, antes da validação semântica. */
sealed interface AiCoachGatewayResult {
    data class Success(val response: AiCoachResponse) : AiCoachGatewayResult
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
