package com.example.domain.social

/**
 * A fronteira de denúncias sociais com o Spark Backend (T17.6).
 *
 * Denúncias são estritas, minimalistas e sem texto livre.
 * O usuário denunciado não recebe notificação de denúncia.
 */
interface ReportGateway {

    /** `true` quando existe endereço de Spark Backend configurado neste build. */
    val isConfigured: Boolean

    /** Submete uma denúncia contra o usuário identificado por [socialId]. */
    suspend fun reportUser(socialId: String, reason: ReportReason): ReportOutcome
}
