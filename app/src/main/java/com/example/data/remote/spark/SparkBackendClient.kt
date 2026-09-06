package com.example.data.remote.spark

import android.util.Log
import com.example.domain.auth.AuthTokenProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * O cliente HTTP do Spark Backend.
 *
 * Fronteira única para as chamadas online do app: quem precisar de um endpoint novo (T16.2+)
 * adiciona um método aqui e recebe o `Authorization: Bearer` de graça, pelo
 * [SparkAuthInterceptor]. Não existe um segundo lugar montando esse header.
 *
 * ## O que ele não é
 *
 * Não é fonte de verdade de nada. O Perfil sabe se há conta pelo Firebase Auth local, e continua
 * sabendo com a VPS fora do ar — [me] existe para *provar* que a identidade fecha ponta a ponta,
 * não para descobri-la. A UI observa Room e o estado local; ela não observa este cliente.
 *
 * Sem `SPARK_BACKEND_BASE_URL` configurado, todo método responde
 * [SparkBackendResult.NotConfigured] sem abrir conexão nenhuma.
 */
class SparkBackendClient(
    private val baseUrl: String,
    tokens: AuthTokenProvider,
    httpClient: OkHttpClient? = null
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        httpClient ?: OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Nenhum `HttpLoggingInterceptor` neste cliente: ele imprimiria o header
            // `Authorization` no Logcat, inclusive em debug.
            .addInterceptor(SparkAuthInterceptor(tokens))
            .build()
    }

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /**
     * `GET /v1/auth/me` — o uid que o **servidor** concluiu, verificando o token pelo Firebase
     * Admin SDK. Comparar com o uid local é a validação de ponta a ponta da T16.1.
     */
    suspend fun me(): SparkBackendResult<String> = get("v1/auth/me") { body ->
        json.decodeFromString<AuthenticatedIdentity>(body).uid.takeIf { it.isNotBlank() }
    }

    private suspend fun <T> get(
        path: String,
        parse: (String) -> T?
    ): SparkBackendResult<T> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext SparkBackendResult.NotConfigured

        val url = "${baseUrl.trimEnd('/')}/$path"
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_UNAUTHORIZED -> SparkBackendResult.Unauthenticated
                    response.code >= HTTP_SERVER_ERROR -> SparkBackendResult.Unavailable
                    !response.isSuccessful -> SparkBackendResult.Failure(response.code)
                    else -> {
                        val value = runCatching { parse(response.body?.string().orEmpty()) }.getOrNull()
                        if (value == null) {
                            SparkBackendResult.Failure(null)
                        } else {
                            SparkBackendResult.Success(value)
                        }
                    }
                }
            }
        } catch (e: MissingAuthTokenException) {
            // Sem conta conectada não existe requisição autenticada a fazer.
            SparkBackendResult.Unauthenticated
        } catch (e: IOException) {
            // Backend fora do ar não é identidade apagada: quem estava logado continua logado.
            Log.i(TAG, "Spark Backend indisponível: ${e.javaClass.simpleName}")
            SparkBackendResult.Unavailable
        }
    }

    @Serializable
    private data class AuthenticatedIdentity(val uid: String)

    companion object {
        const val TAG = "SparkBackend"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_SERVER_ERROR = 500
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
    }
}
