package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.ReportError
import com.example.domain.social.ReportGateway
import com.example.domain.social.ReportOutcome
import com.example.domain.social.ReportReason
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A fronteira HTTP de denúncias sociais com o Spark Backend (T17.6).
 *
 * Denúncias são minimalistas e auditadas na VPS.
 * O usuário denunciado não é notificado.
 */
class SparkReportGateway(
    private val client: SparkBackendClient?
) : ReportGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun reportUser(socialId: String, reason: ReportReason): ReportOutcome {
        val activeClient = client ?: return ReportOutcome.Failure(ReportError.NOT_CONFIGURED)
        val requestBody = json.encodeToString(CreateReportRequestDto(reportedSocialId = socialId, reason = reason.name))
        val outcome = activeClient.postJson(ReportContract.REPORTS_PATH, requestBody)
        return interpret(outcome)
    }

    private fun interpret(outcome: SparkHttpOutcome): ReportOutcome = when (outcome) {
        SparkHttpOutcome.NotConfigured -> ReportOutcome.Failure(ReportError.NOT_CONFIGURED)
        SparkHttpOutcome.SignedOut -> ReportOutcome.Failure(ReportError.AUTH_REQUIRED)
        SparkHttpOutcome.NetworkFailure -> ReportOutcome.Failure(ReportError.NETWORK)
        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                val dto = json.decodeFromString<CreateReportResponseDto>(outcome.body)
                if (dto.result == "REPORT_RECEIVED") Unit else null
            } catch (e: SerializationException) {
                null
            }
            if (parsed == null) {
                ReportOutcome.Failure(ReportError.REJECTED)
            } else {
                ReportOutcome.Success
            }
        } else {
            ReportOutcome.Failure(errorOf(outcome))
        }
    }

    private fun errorOf(outcome: SparkHttpOutcome.Response): ReportError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialContract.ErrorCodes.UNAUTHENTICATED -> ReportError.AUTH_REQUIRED
            SocialContract.ErrorCodes.AUTH_UNAVAILABLE -> ReportError.UNAVAILABLE
            SocialContract.ErrorCodes.API_RATE_LIMITED -> ReportError.RATE_LIMITED
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED,
            ReportContract.ErrorCodes.SOCIAL_NOT_ENABLED -> ReportError.SOCIAL_NOT_ENABLED
            ReportContract.ErrorCodes.SOCIAL_PROFILE_DISABLED -> ReportError.SOCIAL_DISABLED
            ReportContract.ErrorCodes.SOCIAL_PROFILE_NOT_FOUND -> ReportError.PROFILE_NOT_FOUND
            ReportContract.ErrorCodes.CANNOT_REPORT_SELF -> ReportError.CANNOT_REPORT_SELF
            ReportContract.ErrorCodes.NO_LEGITIMATE_CONTEXT -> ReportError.NO_LEGITIMATE_CONTEXT
            ReportContract.ErrorCodes.REPORT_RATE_LIMITED -> ReportError.RATE_LIMITED
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> ReportError.AUTH_REQUIRED
                outcome.code == HTTP_TOO_MANY_REQUESTS -> ReportError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> ReportError.UNAVAILABLE
                else -> ReportError.REJECTED
            }
        }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
