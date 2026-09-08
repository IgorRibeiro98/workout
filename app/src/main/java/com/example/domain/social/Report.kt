package com.example.domain.social

/** Motivos válidos e limitados para denúncia (T17.6). Sem texto livre. */
enum class ReportReason {
    SPAM,
    HARASSMENT,
    INAPPROPRIATE_BEHAVIOR,
    OTHER
}

/** Desfecho de denúncia social. */
sealed interface ReportOutcome {
    data object Success : ReportOutcome
    data class Failure(val error: ReportError) : ReportOutcome
}

/** Erros na submissão de denúncia. */
enum class ReportError {
    NOT_CONFIGURED,
    AUTH_REQUIRED,
    SOCIAL_NOT_ENABLED,
    SOCIAL_DISABLED,
    PROFILE_NOT_FOUND,
    CANNOT_REPORT_SELF,
    NO_LEGITIMATE_CONTEXT,
    RATE_LIMITED,
    REJECTED,
    UNAVAILABLE,
    NETWORK
}
