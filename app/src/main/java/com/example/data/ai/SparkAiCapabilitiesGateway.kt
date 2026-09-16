package com.example.data.ai

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.ai.AiCapabilitiesGateway
import com.example.domain.ai.model.AiCapabilitiesErrorKind
import com.example.domain.ai.model.AiCapabilitiesGatewayResult
import com.example.domain.ai.model.AiCapability
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A fronteira de produção com `GET /v1/account/capabilities` (T19.0).
 *
 * ```text
 * AiCapabilitiesViewModel → aqui → GET v1/account/capabilities (Bearer <Firebase ID Token>)
 *   → Spark Backend → AiEntitlementResolver → resposta account-scoped
 * ```
 *
 * Mesma fronteira HTTP que [SparkBackendAiCoachGateway] — um cliente, um interceptor, um lugar
 * montando `Authorization: Bearer`. Sem conta conectada, [SparkBackendClient] nem abre a
 * requisição: o resultado é [AiCapabilitiesErrorKind.AUTH_REQUIRED], zero rede.
 *
 * Uma capability que este build não reconhece (nome fora de [AiCapability]) é ignorada, nunca
 * tratada como liberada: o app só age sobre o que ele sabe interpretar.
 */
class SparkAiCapabilitiesGateway(
    private val client: SparkBackendClient?
) : AiCapabilitiesGateway {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(): AiCapabilitiesGatewayResult {
        val backend = client
        if (backend == null || !backend.isConfigured) {
            return AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.UNAVAILABLE)
        }

        return when (val outcome = backend.getJson(CAPABILITIES_PATH)) {
            SparkHttpOutcome.NotConfigured ->
                AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.UNAVAILABLE)

            // Sem conta conectada a requisição nem sai: zero rede, zero custo — igual ao Coach.
            SparkHttpOutcome.SignedOut ->
                AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.AUTH_REQUIRED)

            SparkHttpOutcome.NetworkFailure ->
                AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.NETWORK)

            is SparkHttpOutcome.Response -> parse(outcome)
        }
    }

    private fun parse(response: SparkHttpOutcome.Response): AiCapabilitiesGatewayResult {
        if (response.code == HTTP_UNAUTHORIZED) {
            return AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.AUTH_REQUIRED)
        }
        if (response.code != HTTP_OK) {
            val kind = if (response.code >= HTTP_SERVER_ERROR) {
                AiCapabilitiesErrorKind.UNAVAILABLE
            } else {
                AiCapabilitiesErrorKind.INVALID_RESPONSE
            }
            return AiCapabilitiesGatewayResult.Error(kind)
        }

        return try {
            val envelope = json.parseToJsonElement(response.body).jsonObject
            val entries = envelope["capabilities"]?.jsonArray
                ?: return AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.INVALID_RESPONSE)

            val allowed = mutableSetOf<AiCapability>()
            for (entry in entries) {
                val fields = entry.jsonObject
                val name = fields["capability"]?.jsonPrimitive?.contentOrNull ?: continue
                val isAllowed = fields["allowed"]?.jsonPrimitive?.booleanOrNull ?: continue
                val capability = runCatching { AiCapability.valueOf(name) }.getOrNull() ?: continue
                if (isAllowed) {
                    allowed += capability
                }
            }
            AiCapabilitiesGatewayResult.Success(allowed)
        } catch (e: SerializationException) {
            AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.INVALID_RESPONSE)
        } catch (e: IllegalArgumentException) {
            AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.INVALID_RESPONSE)
        }
    }

    companion object {
        const val CAPABILITIES_PATH = "v1/account/capabilities"
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_SERVER_ERROR = 500
    }
}
