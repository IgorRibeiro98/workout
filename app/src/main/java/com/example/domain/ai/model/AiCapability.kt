package com.example.domain.ai.model

/**
 * As capabilities de IA que o backend autoriza por conta (T19.0).
 *
 * Espelha `AI_CAPABILITIES` em `backend/src/modules/ai/entitlement/ai-capability.ts` — os dois
 * lados são o mesmo contrato, do mesmo jeito que [AiCoachRequestType] já espelha
 * `AI_COACH_REQUEST_TYPES`. O Android nunca decide entitlement: ele só lê o que o servidor
 * respondeu, para construir uma UX coerente. Toda chamada real continua sendo autorizada de novo
 * no backend.
 */
enum class AiCapability {
    AI_ANALYZE_WORKOUT,
    AI_GENERATE_WORKOUT,
    AI_ADAPT_WORKOUT,
    AI_EXPLAIN
}

/**
 * A capability exigida por esta operação do Coach (T19.0 §6.5) — o mesmo mapeamento de
 * `capabilityFor()` no backend, mantido separado do backend de propósito: os dois lados precisam
 * concordar, mas nenhum importa o outro.
 *
 * Um [AiCoachRequestType] novo sem entrada aqui não compila: `when` sobre um enum é exaustivo no
 * Kotlin, e é isso que impede uma operação nova cair silenciosamente numa capability genérica.
 */
val AiCoachRequestType.requiredCapability: AiCapability
    get() = when (this) {
        AiCoachRequestType.ANALYZE_WORKOUT -> AiCapability.AI_ANALYZE_WORKOUT
        AiCoachRequestType.GENERATE_WORKOUT -> AiCapability.AI_GENERATE_WORKOUT
        AiCoachRequestType.ADAPT_WORKOUT -> AiCapability.AI_ADAPT_WORKOUT
        AiCoachRequestType.EXPLAIN_RECOMMENDATION,
        AiCoachRequestType.EXPLAIN_WORKOUT,
        AiCoachRequestType.EXPLAIN_ADAPTATION,
        AiCoachRequestType.EXPLAIN_PROGRESS -> AiCapability.AI_EXPLAIN
    }

/** Por que uma consulta de capabilities não produziu um resultado utilizável. */
enum class AiCapabilitiesErrorKind {
    /** Sem conta conectada — capabilities de IA exigem Conta Spark, como o resto do Coach. */
    AUTH_REQUIRED,
    /** Backend indisponível, não configurado neste build, ou o servidor não conseguiu decidir. */
    UNAVAILABLE,
    /** Sem conectividade ou falha de transporte. */
    NETWORK,
    /** A resposta chegou, mas não pôde ser interpretada com segurança. */
    INVALID_RESPONSE
}

/** O resultado bruto de `GET /v1/account/capabilities`. */
sealed interface AiCapabilitiesGatewayResult {
    /** As capabilities que o servidor confirmou liberadas. Ausência = não liberada. */
    data class Success(val allowed: Set<AiCapability>) : AiCapabilitiesGatewayResult
    data class Error(val kind: AiCapabilitiesErrorKind) : AiCapabilitiesGatewayResult
}
