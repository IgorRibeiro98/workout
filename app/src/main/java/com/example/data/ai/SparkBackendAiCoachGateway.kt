package com.example.data.ai

import android.util.Log
import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.ai.AiCoachGateway
import com.example.domain.ai.AiCoachTimeoutException
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiCoachCallMetadata
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationRequest
import com.example.domain.ai.model.AiCoachExplanationResponse
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiGeneratedWorkoutResponse
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutAdaptationRequest
import com.example.domain.ai.model.AiWorkoutAdaptationResponse
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AiWorkoutGenerationRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A fronteira do Coach com o **Spark Backend** — o caminho de produção a partir da T16.2.
 *
 * ```text
 * Room (autoridade) → ContextBuilder → AiCoachRequest
 *   → aqui → POST /v1/ai/coach (Bearer <Firebase ID Token>)
 *   → Spark Backend → prompt/modelo do servidor → Gemini
 *   → validação no servidor → resposta crua → AiCoachResponseValidator (aqui) → UI
 * ```
 *
 * ## O que mudou, e o que não
 *
 * Mudou quem fala com o Gemini: o app não fala mais. Prompt, modelo, temperatura, esforço de
 * raciocínio, versão de prompt e a credencial vivem no servidor. Este arquivo envia **contexto e
 * intenção**, e nada além disso.
 *
 * Não mudou quem monta o contexto (os `ContextBuilder`, sobre o Room) nem quem valida a resposta
 * no fim (`AiCoachResponseValidator`, contra o domínio atual). A validação do servidor não
 * substitui a daqui: são camadas diferentes olhando coisas diferentes, e o app pode recusar uma
 * resposta que o servidor aceitou — o treino pode ter mudado enquanto o modelo pensava.
 *
 * ## Sem conta, sem chamada
 *
 * Sem sessão do Firebase, o interceptor não deixa a requisição sair e o resultado é
 * [AiCoachErrorKind.AUTH_REQUIRED] — **zero** requisição de rede e zero custo. Isso não afeta
 * treino, execução, histórico, templates nem gamificação, que continuam locais e completos.
 *
 * ## Sem retry, sem fallback
 *
 * Uma ação explícita do usuário produz no máximo uma requisição HTTP e, no servidor, no máximo
 * uma chamada ao modelo. Não há repetição automática aqui, e não existe caminho escondido de
 * volta para o Firebase AI Logic: se o backend não responde, o Coach fica indisponível e diz isso.
 */
class SparkBackendAiCoachGateway(
    private val client: SparkBackendClient?,
    /** Um id por tentativa lógica do usuário. Aleatório, opaco e sem dado pessoal. */
    private val clientRequestIdProvider: () -> String = { newClientRequestId() }
) : AiCoachGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
        explicitNulls = true
    }

    override suspend fun request(request: AiCoachRequest): AiCoachGatewayResult {
        val outcome = call(
            requestType = AiCoachRequestType.ANALYZE_WORKOUT,
            schemaVersion = request.schemaVersion,
            context = json.encodeToJsonElement(
                com.example.domain.ai.model.AiCoachContext.serializer(),
                request.context
            ),
            resultSerializer = AiCoachResponse.serializer()
        )
        return when (outcome) {
            is CallOutcome.Failure -> AiCoachGatewayResult.Error(outcome.kind, outcome.detail)
            is CallOutcome.Success -> AiCoachGatewayResult.Success(outcome.result, outcome.metadata)
        }
    }

    override suspend fun generateWorkout(
        request: AiWorkoutGenerationRequest
    ): AiWorkoutGenerationGatewayResult {
        val outcome = call(
            requestType = AiCoachRequestType.GENERATE_WORKOUT,
            schemaVersion = request.schemaVersion,
            context = json.encodeToJsonElement(
                com.example.domain.ai.model.AiWorkoutGenerationContext.serializer(),
                request.context
            ),
            resultSerializer = AiGeneratedWorkoutResponse.serializer()
        )
        return when (outcome) {
            is CallOutcome.Failure ->
                AiWorkoutGenerationGatewayResult.Error(outcome.kind, outcome.detail)

            is CallOutcome.Success ->
                AiWorkoutGenerationGatewayResult.Success(outcome.result, outcome.metadata)
        }
    }

    override suspend fun adaptWorkout(
        request: AiWorkoutAdaptationRequest
    ): AiWorkoutAdaptationGatewayResult {
        val outcome = call(
            requestType = AiCoachRequestType.ADAPT_WORKOUT,
            schemaVersion = request.schemaVersion,
            context = json.encodeToJsonElement(
                com.example.domain.ai.model.AiWorkoutAdaptationContext.serializer(),
                request.context
            ),
            resultSerializer = AiWorkoutAdaptationResponse.serializer()
        )
        return when (outcome) {
            is CallOutcome.Failure ->
                AiWorkoutAdaptationGatewayResult.Error(outcome.kind, outcome.detail)

            is CallOutcome.Success ->
                AiWorkoutAdaptationGatewayResult.Success(outcome.result, outcome.metadata)
        }
    }

    override suspend fun explain(
        request: AiCoachExplanationRequest
    ): AiCoachExplanationGatewayResult {
        val outcome = call(
            requestType = request.type,
            schemaVersion = request.schemaVersion,
            context = json.encodeToJsonElement(
                com.example.domain.ai.model.AiCoachExplanationContext.serializer(),
                request.context
            ),
            resultSerializer = AiCoachExplanationResponse.serializer()
        )
        return when (outcome) {
            is CallOutcome.Failure ->
                AiCoachExplanationGatewayResult.Error(outcome.kind, outcome.detail)

            is CallOutcome.Success ->
                AiCoachExplanationGatewayResult.Success(outcome.result, outcome.metadata)
        }
    }

    /** O que uma chamada produziu, antes de virar o resultado específico de cada tipo. */
    private sealed interface CallOutcome<out T> {
        data class Success<T>(val result: T, val metadata: AiCoachCallMetadata) : CallOutcome<T>
        data class Failure(val kind: AiCoachErrorKind, val detail: String?) : CallOutcome<Nothing>
    }

    /**
     * Uma requisição: mesmo contrato, mesmo timeout, mesmo mapeamento de erro para os quatro
     * tipos. O que muda por tipo é o contexto enviado e o schema do resultado.
     */
    private suspend fun <T> call(
        requestType: AiCoachRequestType,
        schemaVersion: Int,
        context: JsonElement,
        resultSerializer: KSerializer<T>
    ): CallOutcome<T> {
        // A guarda de contrato acontece antes de qualquer rede: uma versão que este build não
        // sabe interpretar produziria resposta que ninguém aqui consegue validar.
        if (!AiModelConfig.isSupportedSchemaVersion(schemaVersion)) {
            return CallOutcome.Failure(
                AiCoachErrorKind.INVALID_RESPONSE,
                "schemaVersion não suportada neste build: $schemaVersion"
            )
        }

        val backend = client
        if (backend == null || !backend.isConfigured) {
            return CallOutcome.Failure(
                AiCoachErrorKind.UNAVAILABLE,
                "Coach online não configurado neste build"
            )
        }

        val clientRequestId = clientRequestIdProvider()
        val body = buildJsonObject {
            put("clientRequestId", clientRequestId)
            put("requestType", requestType.name)
            put("schemaVersion", schemaVersion)
            put("context", context)
        }

        val outcome = try {
            com.example.domain.ai.AiCoachCall.withTimeout {
                backend.postJson(COACH_PATH, json.encodeToString(JsonObject.serializer(), body))
            }
        } catch (e: CancellationException) {
            // Sair da tela cancela a corrotina. Nada é reenviado e nenhuma tela é alterada.
            throw e
        }

        outcome.exceptionOrNull()?.let { error ->
            if (error is AiCoachTimeoutException) {
                return CallOutcome.Failure(
                    AiCoachErrorKind.TIMEOUT,
                    "sem resposta em ${error.timeoutMs} ms"
                )
            }
            return CallOutcome.Failure(AiCoachErrorKind.PROVIDER, null)
        }

        return when (val http = outcome.getOrNull()) {
            null -> CallOutcome.Failure(AiCoachErrorKind.PROVIDER, null)

            SparkHttpOutcome.NotConfigured -> CallOutcome.Failure(
                AiCoachErrorKind.UNAVAILABLE,
                "Coach online não configurado neste build"
            )

            // Sem conta conectada a requisição nem sai: zero rede, zero custo.
            SparkHttpOutcome.SignedOut -> CallOutcome.Failure(AiCoachErrorKind.AUTH_REQUIRED, null)

            SparkHttpOutcome.NetworkFailure -> CallOutcome.Failure(AiCoachErrorKind.NETWORK, null)

            is SparkHttpOutcome.Response -> parse(http, clientRequestId, resultSerializer)
        }
    }

    private fun <T> parse(
        response: SparkHttpOutcome.Response,
        clientRequestId: String,
        resultSerializer: KSerializer<T>
    ): CallOutcome<T> {
        if (response.code != HTTP_OK && response.code != HTTP_CREATED) {
            return CallOutcome.Failure(errorKindOf(response.code), errorCodeOf(response.body))
        }

        val envelope = try {
            json.parseToJsonElement(response.body).jsonObject
        } catch (e: SerializationException) {
            return CallOutcome.Failure(AiCoachErrorKind.INVALID_RESPONSE, "envelope ilegível")
        } catch (e: IllegalArgumentException) {
            return CallOutcome.Failure(AiCoachErrorKind.INVALID_RESPONSE, "envelope ilegível")
        }

        val declaredSchema = envelope["schemaVersion"]?.jsonPrimitive?.intOrNull
        if (declaredSchema != null && !AiModelConfig.isSupportedSchemaVersion(declaredSchema)) {
            return CallOutcome.Failure(
                AiCoachErrorKind.INVALID_RESPONSE,
                "schemaVersion da resposta não suportada: $declaredSchema"
            )
        }

        val echoed = envelope["clientRequestId"]?.jsonPrimitive?.contentOrNull
        if (echoed != null && echoed != clientRequestId) {
            // Resposta de outra requisição não é aproveitada: identidade de chamada importa.
            return CallOutcome.Failure(AiCoachErrorKind.INVALID_RESPONSE, "resposta fora de ordem")
        }

        val result = envelope["result"]
            ?: return CallOutcome.Failure(AiCoachErrorKind.INVALID_RESPONSE, "resposta sem result")

        val decoded = try {
            json.decodeFromJsonElement(resultSerializer, result)
        } catch (e: SerializationException) {
            return CallOutcome.Failure(AiCoachErrorKind.INVALID_RESPONSE, "result fora do contrato")
        } catch (e: IllegalArgumentException) {
            return CallOutcome.Failure(AiCoachErrorKind.INVALID_RESPONSE, "result fora do contrato")
        }

        return CallOutcome.Success(
            result = decoded,
            metadata = AiCoachCallMetadata(
                requestId = envelope["requestId"]?.jsonPrimitive?.contentOrNull,
                model = envelope["model"]?.jsonPrimitive?.contentOrNull
                    ?: AiCoachCallMetadata.UNKNOWN_MODEL,
                promptVersion = envelope["promptVersion"]?.jsonPrimitive?.intOrNull
                    ?: AiCoachCallMetadata.UNKNOWN_PROMPT_VERSION
            )
        )
    }

    /**
     * Status HTTP vira a taxonomia que a UI já conhece desde a T14.
     *
     * Nenhum detalhe do servidor ou do SDK do Gemini atravessa: o que sobe é a classe do erro e,
     * quando existe, o `code` do envelope — que é vocabulário do Spark, não do provider.
     */
    private fun errorKindOf(code: Int): AiCoachErrorKind = when (code) {
        HTTP_UNAUTHORIZED -> AiCoachErrorKind.AUTH_REQUIRED
        HTTP_BAD_REQUEST -> AiCoachErrorKind.INVALID_RESPONSE
        HTTP_CONFLICT -> AiCoachErrorKind.PROVIDER
        HTTP_UNPROCESSABLE -> AiCoachErrorKind.INVALID_RESPONSE
        HTTP_TOO_MANY_REQUESTS -> AiCoachErrorKind.RATE_LIMITED
        HTTP_GATEWAY_TIMEOUT -> AiCoachErrorKind.TIMEOUT
        HTTP_BAD_GATEWAY, HTTP_UNAVAILABLE -> AiCoachErrorKind.UNAVAILABLE
        else -> AiCoachErrorKind.PROVIDER
    }

    /** Só o `code` do envelope de erro. Mensagem do servidor não é texto de UI. */
    private fun errorCodeOf(body: String): String? = try {
        json.parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.contentOrNull
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        const val COACH_PATH = "v1/ai/coach"

        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_BAD_GATEWAY = 502
        const val HTTP_UNAVAILABLE = 503
        const val HTTP_GATEWAY_TIMEOUT = 504

        /**
         * Um identificador aleatório por tentativa lógica.
         *
         * Aleatório e opaco de propósito: timestamp isolado colide em toque duplo, e nome de
         * usuário ou hash de dado pessoal transformaria correlação técnica em identificador de
         * pessoa. O formato é o que o servidor aceita (`[A-Za-z0-9._-]{8,128}`).
         */
        fun newClientRequestId(): String = "cli-${UUID.randomUUID()}"
    }
}
