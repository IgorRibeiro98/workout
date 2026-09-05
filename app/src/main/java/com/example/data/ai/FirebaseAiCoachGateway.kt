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
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiCoachResponse
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
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
 * Enquanto a configuração externa do Firebase não existir (`google-services.json` + plugin
 * `com.google.gms.google-services`), toda chamada responde
 * [AiCoachErrorKind.UNAVAILABLE] e o resto do Spark segue funcionando offline.
 */
class FirebaseAiCoachGateway(
    private val context: Context,
    private val appCheckEnabled: Boolean = false
) : AiCoachGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    @Volatile
    private var cachedModel: GenerativeModel? = null

    @Volatile
    private var appCheckInstalled: Boolean = false

    override suspend fun request(request: AiCoachRequest): AiCoachGatewayResult {
        val model = try {
            obtainModel()
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
                model.generateContent(AiCoachPrompt.userPrompt(request)).text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RequestTimeoutException) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.TIMEOUT, e.message)
        } catch (e: QuotaExceededException) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.RATE_LIMITED, e.message)
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
            return AiCoachGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
        } catch (e: ResponseStoppedException) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
        } catch (e: ContentBlockedException) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
        } catch (e: ServerException) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.PROVIDER, e.message)
        } catch (e: IOException) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.NETWORK, e.message)
        } catch (e: Exception) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.PROVIDER, e.message)
        }

        call.exceptionOrNull()?.let { error ->
            if (error is AiCoachTimeoutException) return AiCoachCall.timeoutError(error)
        }

        val rawText = call.getOrNull()
        if (rawText.isNullOrBlank()) {
            return AiCoachGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, "resposta vazia")
        }

        return try {
            AiCoachGatewayResult.Success(json.decodeFromString<AiCoachResponse>(rawText))
        } catch (e: Exception) {
            AiCoachGatewayResult.Error(AiCoachErrorKind.INVALID_RESPONSE, e.message)
        }
    }

    private fun obtainModel(): GenerativeModel {
        val app = try {
            FirebaseApp.getInstance()
        } catch (e: Exception) {
            try {
                FirebaseApp.initializeApp(
                    context.applicationContext,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:638822756779:android:3cd9d02c9fbaa5342fa2ee")
                        .setApiKey("AIzaSyD5y3HrGeQBWHC5vuxhDzhXj2LVj9ZIq2g")
                        .setProjectId("spark-36b11")
                        .setStorageBucket("spark-36b11.firebasestorage.app")
                        .build()
                )
            } catch (initErr: Exception) {
                FirebaseApp.getInstance()
            }
        }
        installAppCheck(app)

        return FirebaseAI.getInstance(app, GenerativeBackend.googleAI()).generativeModel(
            modelName = AiModelConfig.MODEL_NAME,
            generationConfig = generationConfig {
                temperature = AiModelConfig.TEMPERATURE
                maxOutputTokens = AiModelConfig.MAX_OUTPUT_TOKENS
                responseMimeType = APPLICATION_JSON
                responseSchema = AiCoachResponseSchema.schema
                thinkingConfig = thinkingConfig {
                    thinkingLevel = AiModelConfig.THINKING_LEVEL.toFirebaseThinkingLevel()
                }
            },
            systemInstruction = content { text(AiCoachPrompt.systemInstruction()) }
        )
    }

    /**
     * Instala o provedor de App Check para proteger a chamada ao Firebase AI.
     * Em ambiente de desenvolvimento/debug, utiliza DebugAppCheckProviderFactory com o token
     * de depuração ativo.
     */
    private fun installAppCheck(app: FirebaseApp) {
        try {
            val debugSecret = getCurrentDebugToken(context)
            val prefNames = listOf(
                "com.google.firebase.appcheck.debug.DebugAppCheckProvider:${app.persistenceKey}",
                "com.google.firebase.appcheck.debug.DebugAppCheckProvider",
                "com.google.firebase.appcheck.debug.store",
                "com.google.firebase.appcheck.debug.store.${app.options.applicationId}"
            )
            for (prefName in prefNames) {
                context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    .edit()
                    .putString(DEBUG_SECRET_KEY, debugSecret)
                    .apply()
            }

            if (!appCheckInstalled) {
                val providerFactory = try {
                    DebugAppCheckProviderFactory.getInstance()
                } catch (e: Exception) {
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                }
                FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(providerFactory)
                appCheckInstalled = true
            }
            Log.d(TAG, "App Check instalado com sucesso (Debug Secret: $debugSecret)")
        } catch (e: Exception) {
            Log.w(TAG, "App Check indisponível: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun unavailable(error: Throwable): AiCoachGatewayResult.Error {
        Log.w(TAG, "Coach indisponível: ${error.javaClass.simpleName}")
        return AiCoachGatewayResult.Error(
            kind = AiCoachErrorKind.UNAVAILABLE,
            detail = error.message
        )
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
        const val APP_CHECK_DEBUG_TOKEN = "A46F1FA0-8805-42A2-8672-9AD18D352B47"
        const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
        private const val CUSTOM_DEBUG_TOKEN_PREF = "spark_firebase_app_check_custom"
        private const val CUSTOM_DEBUG_TOKEN_KEY = "custom_debug_token"

        fun getCurrentDebugToken(context: Context): String {
            val custom = context.getSharedPreferences(CUSTOM_DEBUG_TOKEN_PREF, Context.MODE_PRIVATE)
                .getString(CUSTOM_DEBUG_TOKEN_KEY, null)
            if (!custom.isNullOrBlank()) {
                return custom
            }
            return APP_CHECK_DEBUG_TOKEN
        }

        fun setCustomDebugToken(context: Context, token: String) {
            val clean = token.trim()
            context.getSharedPreferences(CUSTOM_DEBUG_TOKEN_PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(CUSTOM_DEBUG_TOKEN_KEY, clean.ifBlank { null })
                .apply()

            val actualToken = clean.ifBlank { APP_CHECK_DEBUG_TOKEN }
            val app = try { FirebaseApp.getInstance() } catch (e: Exception) { null }
            val prefNames = mutableListOf(
                "com.google.firebase.appcheck.debug.DebugAppCheckProvider",
                "com.google.firebase.appcheck.debug.store"
            )
            if (app != null) {
                prefNames.add("com.google.firebase.appcheck.debug.DebugAppCheckProvider:${app.persistenceKey}")
                prefNames.add("com.google.firebase.appcheck.debug.store.${app.options.applicationId}")
            }
            for (prefName in prefNames) {
                context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    .edit()
                    .putString(DEBUG_SECRET_KEY, actualToken)
                    .apply()
            }
        }
    }
}
