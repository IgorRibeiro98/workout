package com.example.data.remote.spark

import android.util.Log
import com.example.domain.auth.AuthTokenProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

    /**
     * `POST` autenticado com corpo JSON, devolvendo status e corpo crus.
     *
     * Existe separado de [get] porque o Coach (T16.2) precisa **distinguir** 401, 409, 422, 429,
     * 503 e 504 para mapear em erro tipado na UI, e [SparkBackendResult] colapsa 5xx em
     * "indisponível" de propósito — o que serve à verificação de identidade e não serviria aqui.
     *
     * O que continua igual: um cliente HTTP, um interceptor, um lugar montando
     * `Authorization: Bearer`. Nenhum caminho novo de autenticação foi criado.
     */
    suspend fun postJson(path: String, jsonBody: String): SparkHttpOutcome =
        sendJson("POST", path, jsonBody)

    /**
     * `PATCH` autenticado com corpo JSON, devolvendo status e corpo crus.
     *
     * Existe desde a T17.0: o perfil social é alterado por campo (nome; privacidade parcial), e
     * `PATCH` é o verbo que diz isso. Um `POST` no lugar sugeriria substituição do recurso inteiro
     * — que é justamente o que o servidor **não** faz, já que identidade e timestamps são dele.
     *
     * Mesmo caminho de [postJson]: um cliente, um interceptor, um lugar montando
     * `Authorization: Bearer`.
     */
    suspend fun patchJson(path: String, jsonBody: String): SparkHttpOutcome =
        sendJson("PATCH", path, jsonBody)

    /**
     * `PUT` autenticado com corpo JSON, devolvendo status e corpo crus.
     *
     * Existe desde a T17.9, para a reação de um check-in. `PUT`, e não `POST`, porque a operação é
     * idempotente e descreve o **estado** da reação daquela pessoa naquela publicação: mandar
     * `FIRE` duas vezes deixa o mesmo estado, e `MUSCLE` depois de `FIRE` substitui. É a semântica
     * do verbo, e é a mesma no servidor.
     *
     * Mesmo caminho de [postJson]: um cliente, um interceptor, um lugar montando
     * `Authorization: Bearer`.
     */
    suspend fun putJson(path: String, jsonBody: String): SparkHttpOutcome =
        sendJson("PUT", path, jsonBody)

    /**
     * `DELETE` autenticado **com** corpo JSON, devolvendo status e corpo crus.
     *
     * Existe desde a T17.12, para remover a reação de um check-in: a remoção precisa dizer de qual
     * audiência ela é (§16), e essa informação vai no corpo em vez do query string — um caminho
     * carrega a audiência para dentro de cache e log de proxy, e ali ela é exatamente o dado que
     * não deveria estar.
     *
     * Mesmo caminho de [postJson]: um cliente, um interceptor, um lugar montando
     * `Authorization: Bearer`.
     */
    suspend fun deleteJson(path: String, jsonBody: String): SparkHttpOutcome =
        sendJson("DELETE", path, jsonBody)

    /**
     * `DELETE` autenticado sem corpo, devolvendo status e corpo crus.
     *
     * Usado para unregister de aparelhos push (T17.5) sob `/v1/social/notifications/devices/:deviceId`.
     */
    suspend fun delete(path: String): SparkHttpOutcome =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext SparkHttpOutcome.NotConfigured

            val url = "${baseUrl.trimEnd('/')}/$path"
            val request = Request.Builder()
                .url(url)
                .delete()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    SparkHttpOutcome.Response(
                        code = response.code,
                        body = response.body?.string().orEmpty()
                    )
                }
            } catch (e: MissingAuthTokenException) {
                SparkHttpOutcome.SignedOut
            } catch (e: IOException) {
                Log.i(TAG, "Spark Backend indisponível: ${e.javaClass.simpleName}")
                SparkHttpOutcome.NetworkFailure
            }
        }

    /**
     * `POST` autenticado com corpo **binário**, devolvendo status e corpo crus (T17.9 §33).
     *
     * Existe para o upload de foto do check-in, e só para ele. O corpo são os bytes da imagem, com
     * o `Content-Type` real do que o aparelho produziu — **não** `multipart/form-data`: o corpo tem
     * um arquivo e nenhum campo, e os dois identificadores cabem no query string. Menos parser
     * entre a rede e a decodificação da imagem, e nenhuma dependência nova dos dois lados.
     *
     * O `Content-Type` aqui **não autoriza nada**: o servidor decodifica os bytes de verdade e
     * recusa o que não for imagem, independentemente do que este cabeçalho afirme (§14).
     */
    suspend fun postBytes(path: String, bytes: ByteArray, mediaType: String): SparkHttpOutcome =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext SparkHttpOutcome.NotConfigured

            val url = "${baseUrl.trimEnd('/')}/$path"
            val request = Request.Builder()
                .url(url)
                .post(bytes.toRequestBody(mediaType.toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    SparkHttpOutcome.Response(
                        code = response.code,
                        // Só o corpo da **resposta** — que é JSON pequeno. Os bytes enviados nunca
                        // vão para log, e este cliente não tem interceptor de log.
                        body = response.body?.string().orEmpty()
                    )
                }
            } catch (e: MissingAuthTokenException) {
                SparkHttpOutcome.SignedOut
            } catch (e: IOException) {
                Log.i(TAG, "Spark Backend indisponível: ${e.javaClass.simpleName}")
                SparkHttpOutcome.NetworkFailure
            }
        }

    /**
     * `GET` autenticado que devolve os **bytes** da resposta, com teto (T17.9 §49).
     *
     * Existe para a foto de um check-in. Diferente de [getToFile] (restore, T16.5) de propósito: a
     * imagem é pequena, é desenhada e descartada, e não pode encostar no disco — §56 pede que a
     * mídia social autenticada não tenha cache persistente compartilhado entre contas, e o jeito
     * mais seguro de garantir isso é ela nunca ser escrita.
     *
     * O teto é aplicado **durante** a leitura: recusar depois de já ter alocado o buffer inteiro
     * seria descobrir tarde demais que a resposta era absurda.
     */
    suspend fun getBytes(path: String, maxBytes: Int): SparkBytesOutcome =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext SparkBytesOutcome.NotConfigured

            val url = "${baseUrl.trimEnd('/')}/$path"
            val request = Request.Builder().url(url).get().build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SparkBytesOutcome.Rejected(
                            code = response.code,
                            body = response.body?.string().orEmpty()
                        )
                    }

                    val source = response.body?.byteStream()
                        ?: return@withContext SparkBytesOutcome.Rejected(response.code, "")

                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    source.use { input ->
                        while (true) {
                            val read = input.read(chunk)
                            if (read <= 0) break
                            if (buffer.size() + read > maxBytes) {
                                return@withContext SparkBytesOutcome.TooLarge
                            }
                            buffer.write(chunk, 0, read)
                        }
                    }
                    SparkBytesOutcome.Downloaded(buffer.toByteArray())
                }
            } catch (e: MissingAuthTokenException) {
                SparkBytesOutcome.SignedOut
            } catch (e: IOException) {
                Log.i(TAG, "Spark Backend indisponível: ${e.javaClass.simpleName}")
                SparkBytesOutcome.NetworkFailure
            }
        }

    private suspend fun sendJson(
        method: String,
        path: String,
        jsonBody: String
    ): SparkHttpOutcome =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext SparkHttpOutcome.NotConfigured

            val url = "${baseUrl.trimEnd('/')}/$path"
            val request = Request.Builder()
                .url(url)
                .method(method, jsonBody.toRequestBody(APPLICATION_JSON))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    SparkHttpOutcome.Response(
                        code = response.code,
                        // O corpo é lido inteiro porque as respostas desta API são pequenas e
                        // limitadas pelo contrato; ele nunca vai para log.
                        body = response.body?.string().orEmpty()
                    )
                }
            } catch (e: MissingAuthTokenException) {
                // Sem conta conectada não existe requisição autenticada a fazer.
                SparkHttpOutcome.SignedOut
            } catch (e: IOException) {
                // Backend fora do ar ou sem rede: recuperável, e o núcleo do Spark não muda.
                Log.i(TAG, "Spark Backend indisponível: ${e.javaClass.simpleName}")
                SparkHttpOutcome.NetworkFailure
            }
        }

    /**
     * `GET` autenticado, devolvendo status e corpo crus.
     *
     * Existe pelo mesmo motivo que [postJson]: o backup (T16.4) precisa **distinguir** 404 (não há
     * backup ainda, que é um estado normal e não um erro) de 401 e de 5xx, e [SparkBackendResult]
     * colapsa isso de propósito.
     *
     * Continua sendo um cliente, um interceptor, um lugar montando `Authorization: Bearer`.
     */
    suspend fun getJson(path: String): SparkHttpOutcome = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext SparkHttpOutcome.NotConfigured

        val url = "${baseUrl.trimEnd('/')}/$path"
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                SparkHttpOutcome.Response(
                    code = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        } catch (e: MissingAuthTokenException) {
            SparkHttpOutcome.SignedOut
        } catch (e: IOException) {
            Log.i(TAG, "Spark Backend indisponível: ${e.javaClass.simpleName}")
            SparkHttpOutcome.NetworkFailure
        }
    }

    /**
     * `GET` autenticado que escreve o corpo **direto em um arquivo**, com teto de bytes.
     *
     * Existe para o restore (T16.5): um snapshot chega para ser validado e aplicado, não para ser
     * concatenado em uma `String` na memória de um celular. O corpo é copiado em blocos, e o
     * contador para no [maxBytes] — recusar durante a escrita é o que evita descobrir que o
     * documento era absurdo depois de já ter gasto o disco do usuário.
     *
     * Só o caminho de sucesso escreve. Em qualquer outro desfecho o arquivo é apagado: um arquivo
     * truncado com cara de download completo é exatamente o que o hash existe para pegar — e é
     * melhor nem existir.
     *
     * Compressão, quando o servidor oferecer, é assunto do OkHttp: nada aqui inventa um formato
     * próprio.
     */
    suspend fun getToFile(path: String, destination: File, maxBytes: Long): SparkDownloadOutcome =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext SparkDownloadOutcome.NotConfigured

            val url = "${baseUrl.trimEnd('/')}/$path"
            val request = Request.Builder().url(url).get().build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Erro tem corpo pequeno e estruturado (o envelope da T16.0): ele pode ser
                        // lido inteiro, e é dele que sai o `code` que a UI traduz.
                        return@withContext SparkDownloadOutcome.Rejected(
                            code = response.code,
                            body = response.body?.string().orEmpty()
                        )
                    }

                    val source = response.body?.byteStream()
                        ?: return@withContext SparkDownloadOutcome.Rejected(response.code, "")

                    destination.parentFile?.mkdirs()
                    var total = 0L
                    source.use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > maxBytes) {
                                    output.flush()
                                    destination.delete()
                                    return@withContext SparkDownloadOutcome.TooLarge
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    SparkDownloadOutcome.Downloaded(total)
                }
            } catch (e: MissingAuthTokenException) {
                destination.delete()
                SparkDownloadOutcome.SignedOut
            } catch (e: IOException) {
                // Download interrompido: o arquivo parcial vai embora com ele.
                destination.delete()
                Log.i(TAG, "Download do Spark Backend interrompido: ${e.javaClass.simpleName}")
                SparkDownloadOutcome.NetworkFailure
            }
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
        val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
        const val TAG = "SparkBackend"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_SERVER_ERROR = 500
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
        const val DOWNLOAD_BUFFER_BYTES = 16 * 1024
    }
}
