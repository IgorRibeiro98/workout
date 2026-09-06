package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.domain.ai.AiCoachCall
import com.example.domain.ai.AiCoachGateway
import com.example.domain.ai.AiCoachTimeoutException
import com.example.domain.ai.AiCoachPrompt
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.AiThinkingLevel
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
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.APINotConfiguredException
import com.google.firebase.ai.type.ContentBlockedException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.InvalidStateException
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.UnsupportedUserLocationException
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Único ponto do Spark que conhece Firebase AI Logic e Gemini.
 *
 * Ele recebe [AiCoachRequest], pede structured output ao modelo e devolve
 * [AiCoachGatewayResult]. Não persiste nada, não conhece Room e não toca em nenhuma autoridade
 * do domínio.
 *
 * Sem a configuração externa do Firebase (`google-services.json` + plugin
 * `com.google.gms.google-services`), toda chamada responde
 * [AiCoachErrorKind.UNAVAILABLE] e o resto do Spark segue funcionando offline.
 *
 * A inicialização é sempre tardia: o Firebase só é tocado dentro de uma chamada que o usuário
 * pediu. Abrir o app, navegar ou recompor não inicializa provider nenhum.
 */
class FirebaseAiCoachGateway(
    private val context: Context
) : AiCoachGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    @Volatile
    private var appCheckInstalled: Boolean = false

    override suspend fun request(request: AiCoachRequest): AiCoachGatewayResult {
        unsupportedSchema(request.schemaVersion)?.let {
            return AiCoachGatewayResult.Error(it.kind, it.detail)
        }
        return when (val raw = generate(AiCoachRequestType.ANALYZE_WORKOUT, AiCoachPrompt.userPrompt(request))) {
            is RawResult.Error -> AiCoachGatewayResult.Error(raw.kind, raw.detail)
            is RawResult.Text -> try {
                AiCoachGatewayResult.Success(json.decodeFromString<AiCoachResponse>(raw.text))
            } catch (e: Exception) {
                AiCoachGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
            }
        }
    }

    override suspend fun generateWorkout(
        request: AiWorkoutGenerationRequest
    ): AiWorkoutGenerationGatewayResult {
        unsupportedSchema(request.schemaVersion)?.let {
            return AiWorkoutGenerationGatewayResult.Error(it.kind, it.detail)
        }
        return when (val raw = generate(AiCoachRequestType.GENERATE_WORKOUT, AiCoachPrompt.userPrompt(request))) {
            is RawResult.Error -> AiWorkoutGenerationGatewayResult.Error(raw.kind, raw.detail)
            is RawResult.Text -> try {
                AiWorkoutGenerationGatewayResult.Success(
                    json.decodeFromString<AiGeneratedWorkoutResponse>(raw.text)
                )
            } catch (e: Exception) {
                AiWorkoutGenerationGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
            }
        }
    }

    override suspend fun adaptWorkout(
        request: AiWorkoutAdaptationRequest
    ): AiWorkoutAdaptationGatewayResult {
        unsupportedSchema(request.schemaVersion)?.let {
            return AiWorkoutAdaptationGatewayResult.Error(it.kind, it.detail)
        }
        return when (val raw = generate(AiCoachRequestType.ADAPT_WORKOUT, AiCoachPrompt.userPrompt(request))) {
            is RawResult.Error -> AiWorkoutAdaptationGatewayResult.Error(raw.kind, raw.detail)
            is RawResult.Text -> try {
                AiWorkoutAdaptationGatewayResult.Success(
                    json.decodeFromString<AiWorkoutAdaptationResponse>(raw.text)
                )
            } catch (e: Exception) {
                AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
            }
        }
    }

    override suspend fun explain(
        request: AiCoachExplanationRequest
    ): AiCoachExplanationGatewayResult {
        unsupportedSchema(request.schemaVersion)?.let {
            return AiCoachExplanationGatewayResult.Error(it.kind, it.detail)
        }
        return when (val raw = generate(request.type, AiCoachPrompt.userPrompt(request))) {
            is RawResult.Error -> AiCoachExplanationGatewayResult.Error(raw.kind, raw.detail)
            is RawResult.Text -> try {
                AiCoachExplanationGatewayResult.Success(
                    json.decodeFromString<AiCoachExplanationResponse>(raw.text)
                )
            } catch (e: Exception) {
                AiCoachExplanationGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
            }
        }
    }

    /**
     * A guarda de contrato, antes de qualquer chamada ao provider.
     *
     * Uma versão de schema que este build não conhece não é enviada "para ver no que dá": o
     * schema de saída, o validador e os contextos andam juntos com ela, e conversar em um
     * contrato que o app não sabe interpretar produziria resposta que ninguém pode validar.
     * Recusar aqui é determinístico, custa zero cota e mantém o core intacto.
     */
    private fun unsupportedSchema(schemaVersion: Int): RawResult.Error? =
        if (AiModelConfig.isSupportedSchemaVersion(schemaVersion)) {
            null
        } else {
            RawResult.Error(
                kind = AiCoachErrorKind.INVALID_RESPONSE,
                detail = "schemaVersion não suportada neste build: $schemaVersion"
            )
        }

    /** O texto cru do modelo, ou o erro já traduzido para a taxonomia do Coach. */
    private sealed interface RawResult {
        data class Text(val text: String) : RawResult
        data class Error(val kind: AiCoachErrorKind, val detail: String?) : RawResult
    }

    /**
     * Uma chamada ao provider: mesmo timeout, mesmo tratamento de erro, mesma ausência de retry
     * para os dois tipos de request. O que muda por tipo é o schema e a instrução de sistema.
     */
    private suspend fun generate(type: AiCoachRequestType, prompt: String): RawResult {
        val model = try {
            obtainModel(type)
        } catch (e: IllegalStateException) {
            // FirebaseApp não inicializado: falta a configuração do console.
            return unavailable(e)
        } catch (e: NoClassDefFoundError) {
            return unavailable(e)
        } catch (e: Exception) {
            return unavailable(e)
        }

        val call = try {
            AiCoachCall.withTimeout {
                model.generateContent(prompt).text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RequestTimeoutException) {
            return RawResult.Error(AiCoachErrorKind.TIMEOUT, e.message)
        } catch (e: QuotaExceededException) {
            return RawResult.Error(AiCoachErrorKind.RATE_LIMITED, e.message)
        } catch (e: ServiceDisabledException) {
            return unavailable(e)
        } catch (e: APINotConfiguredException) {
            return unavailable(e)
        } catch (e: InvalidAPIKeyException) {
            return unavailable(e)
        } catch (e: UnsupportedUserLocationException) {
            return unavailable(e)
        } catch (e: InvalidStateException) {
            return unavailable(e)
        } catch (e: PromptBlockedException) {
            return RawResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
        } catch (e: ResponseStoppedException) {
            return RawResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
        } catch (e: ContentBlockedException) {
            return RawResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
        } catch (e: ServerException) {
            return RawResult.Error(AiCoachErrorKind.PROVIDER, e.message)
        } catch (e: IOException) {
            return RawResult.Error(AiCoachErrorKind.NETWORK, e.message)
        } catch (e: Exception) {
            return RawResult.Error(AiCoachErrorKind.PROVIDER, e.message)
        }

        call.exceptionOrNull()?.let { error ->
            if (error is AiCoachTimeoutException) {
                val timeout = AiCoachCall.timeoutError(error)
                return RawResult.Error(timeout.kind, timeout.detail)
            }
        }

        val rawText = call.getOrNull()
        if (rawText.isNullOrBlank()) {
            return RawResult.Error(AiCoachErrorKind.INVALID_RESPONSE, "resposta vazia")
        }
        return RawResult.Text(rawText)
    }

    private fun obtainModel(type: AiCoachRequestType): GenerativeModel {
        // A configuração vem de `google-services.json`, processado pelo plugin do Gradle e
        // inicializado pelo `FirebaseInitProvider`. Nenhuma chave de projeto vive no código:
        // duplicá-la aqui criaria uma segunda autoridade de configuração, silenciosamente
        // divergente do arquivo real. Sem configuração, isto lança e a chamada vira UNAVAILABLE.
        val app = FirebaseApp.getInstance()
        installAppCheck(app)

        return FirebaseAI.getInstance(app, GenerativeBackend.googleAI()).generativeModel(
            modelName = AiModelConfig.MODEL_NAME,
            generationConfig = generationConfig {
                temperature = AiModelConfig.TEMPERATURE
                maxOutputTokens = AiModelConfig.MAX_OUTPUT_TOKENS
                responseMimeType = APPLICATION_JSON
                responseSchema = AiCoachResponseSchema.forType(type)
                thinkingConfig = thinkingConfig {
                    thinkingLevel = AiModelConfig.THINKING_LEVEL.toFirebaseThinkingLevel()
                }
            },
            systemInstruction = content { text(AiCoachPrompt.systemInstruction(type)) }
        )
    }

    /**
     * Instala o provedor de App Check da variante de build, uma vez por processo.
     *
     * Qual provedor é decidido em tempo de **compilação** por [AiCoachAppCheck]: debug usa o
     * provedor de depuração, release usa Play Integrity, e nenhum dos dois consegue aparecer no
     * outro APK. Nenhum segredo é registrado em log.
     */
    private fun installAppCheck(app: FirebaseApp) {
        if (appCheckInstalled) return
        try {
            AiCoachAppCheck.publishDebugToken(context)
            FirebaseAppCheck.getInstance(app)
                .installAppCheckProviderFactory(AiCoachAppCheck.providerFactory())
            appCheckInstalled = true
            Log.i(TAG, "App Check instalado: provider=${AiCoachAppCheck.PROVIDER_NAME}")
        } catch (e: Exception) {
            Log.w(TAG, "App Check indisponível: ${e.javaClass.simpleName}")
        }
    }

    private fun unavailable(error: Throwable): RawResult.Error {
        Log.w(TAG, "Coach indisponível: ${error.javaClass.simpleName}")
        return RawResult.Error(kind = AiCoachErrorKind.UNAVAILABLE, detail = error.message)
    }

    private fun AiThinkingLevel.toFirebaseThinkingLevel(): ThinkingLevel = when (this) {
        AiThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
        AiThinkingLevel.LOW -> ThinkingLevel.LOW
        AiThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
        AiThinkingLevel.HIGH -> ThinkingLevel.HIGH
    }

    companion object {
        const val TAG = "AiCoachGateway"
        const val APPLICATION_JSON = "application/json"
    }
}
